'use client';
import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import Image from 'next/image';
import { useSearchParams, useRouter } from 'next/navigation';
import { LayoutDashboard, Package, ShoppingBag, Truck, Boxes, Settings, ArrowUpRight, Plus, Pencil, Trash2, X } from 'lucide-react';
import { AdminPayments } from './admin-payments';
import { api } from '@/lib/api/client';
import { ApiError, errorMessage } from '@/lib/api/http';
import { adminProductPageSchema, adminProductSchema, categoriesSchema, dashboardSchema, deleteResultSchema, orderPageSchema, orderSchema } from '@/lib/api/schemas';
import { useShop } from '@/stores/shop';
import { FALLBACK_IMAGE, ORDER_STATUS, PHOTO_LIBRARY, formatDate, money, suppliers } from '@/lib/data';
import type { AdminProduct, Category, Dashboard, OrderSummary } from '@/types';

// Admin workspace backed by /api/v1/admin/* (ROLE_ADMIN enforced by the server on every call).
const tabs=[['overview','Resumen',LayoutDashboard],['products','Productos',Package],['orders','Pedidos',ShoppingBag],['payments','Pagos',ShoppingBag],['suppliers','Proveedores',Truck],['inventory','Inventario',Boxes],['settings','Configuración',Settings]] as const;
const PRODUCT_STATUS:Record<AdminProduct['status'],string>={ACTIVE:'Activo',PAUSED:'Pausado',DRAFT:'Borrador',ARCHIVED:'Archivado'};
type AdminVariant=AdminProduct['variants'][number];
type DraftVariant={id?:string;sku?:string;name:string;color?:string|null;price:number;stock:number;compareAtPrice?:number|null;position:number;imageUrl?:string|null};
type Draft={id?:string;status?:AdminProduct['status'];name:string;categoryId:string;description:string;photo:string;photoChanged:boolean;active:boolean;featured:boolean;variants:DraftVariant[]};

const slugify=(text:string)=>text.normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-+|-+$/g,'').slice(0,60)||'objeto';
const variantBody=(v:DraftVariant|AdminVariant,sku:string,imageUrl:string|null|undefined)=>({id:v.id,sku,name:v.name,color:v.color||undefined,price:v.price,compareAtPrice:v.compareAtPrice??undefined,stock:v.stock,active:true,position:v.position,imageUrl:imageUrl||undefined});
const activeVariants=(p:AdminProduct)=>p.variants.filter(v=>v.active);
const units=(p:AdminProduct)=>p.status==='ARCHIVED'?0:activeVariants(p).reduce((n,v)=>n+v.stock,0);

function adminFailure(e:unknown){
  if(e instanceof ApiError){
    if(e.status===401||e.status===403)return 'Tu sesión de administración expiró. Vuelve a iniciar sesión.';
    if(e.status===409)return 'El cambio entra en conflicto con datos existentes (nombre, SKU o estado ya utilizados).';
    if(e.status===422)return 'El cambio no cumple las reglas del catálogo (por ejemplo, un producto activo necesita una variante activa).';
    if(e.status===400)return 'Revisa los datos: hay campos con formato no válido.';
  }
  return errorMessage(e,'No pudimos completar la operación.');
}

export function Admin({adminEmail}:{adminEmail:string}){
  const {announcement,setAnnouncement,logout}=useShop();
  const params=useSearchParams();const router=useRouter();const tab=params.get('view')||'overview';
  const [products,setProducts]=useState<AdminProduct[]|null>(null);
  const [orders,setOrders]=useState<OrderSummary[]>([]);
  const [categories,setCategories]=useState<Category[]>([]);
  const [dashboard,setDashboard]=useState<Dashboard|null>(null);
  const [query,setQuery]=useState('');const [status,setStatus]=useState('all');
  const [editing,setEditing]=useState<Draft|null>(null);
  const [notice,setNotice]=useState('');const [error,setError]=useState('');const [busy,setBusy]=useState(false);const [deleting,setDeleting]=useState('');

  const load=useCallback(async()=>{
    try{
      const [p,o,d,c]=await Promise.all([api.get('/admin/products?size=100',adminProductPageSchema),api.get('/admin/orders?size=100',orderPageSchema),api.get('/admin/dashboard',dashboardSchema),api.get('/admin/categories',categoriesSchema)]);
      setProducts(p.items);setOrders(o.items);setDashboard(d);setCategories(c);
    }catch(e){setProducts(current=>current??[]);setError(adminFailure(e))}
  },[]);
  useEffect(()=>{void load()},[load]);

  /** Runs an admin mutation, then reloads everything so the screen always shows server state. */
  async function run(action:()=>Promise<string>){
    if(busy)return false;
    setBusy(true);setError('');setNotice('');
    try{setNotice(await action());await load();return true}
    catch(e){setError(adminFailure(e));return false}
    finally{setBusy(false)}
  }

  if(products===null)return <div className="page skeleton"/>;
  const categoryName=(id:string)=>categories.find(c=>c.id===id)?.name||'—';
  const filtered=products.filter(p=>p.name.toLowerCase().includes(query.toLowerCase())&&(status==='all'||(p.status==='ACTIVE')===(status==='active')));
  const unitsByCategory=categories.map(c=>({name:c.name,units:products.filter(p=>p.categoryId===c.id).reduce((s,p)=>s+units(p),0)}));
  const title=tabs.find(t=>t[0]===tab)?.[1]||'Resumen';

  function freshDraft():Draft{const photo='/products/vase.jpg';return {name:'',categoryId:categories[0]?.id||'',description:'',photo,photoChanged:true,active:true,featured:false,variants:[{name:'Original',color:'#b6aa94',price:0,stock:1,position:0,imageUrl:photo}]}}
  function toDraft(p:AdminProduct):Draft{return {id:p.id,status:p.status,name:p.name,categoryId:p.categoryId,description:p.description||'',photo:p.images[0]?.url||p.variants[0]?.imageUrl||FALLBACK_IMAGE,photoChanged:false,active:p.status==='ACTIVE',featured:p.featured,variants:activeVariants(p).map(v=>({...v}))}}

  async function saveDraft(d:Draft){
    const images=d.photoChanged?[{url:d.photo,altText:d.name}]:undefined;
    const common={name:d.name.trim(),categoryId:d.categoryId,description:d.description,featured:d.featured,price:d.variants[0].price};
    if(d.id){
      // Keep the current status unless the "Activo" checkbox actually changed it.
      const nextStatus=d.active?'ACTIVE':d.status==='ACTIVE'?'PAUSED':d.status;
      await api.patch(`/admin/products/${d.id}`,adminProductSchema,{...common,status:nextStatus,images,variants:d.variants.map(v=>variantBody(v,v.sku!,d.photoChanged?d.photo:v.imageUrl))});
      return 'Producto guardado. Los cambios ya aparecen en la tienda.';
    }
    const create=(slug:string)=>api.post('/admin/products',adminProductSchema,{...common,slug,status:d.active?'ACTIVE':'PAUSED',images,variants:d.variants.map((v,i)=>variantBody(v,`${slug.toUpperCase()}-${i+1}`,d.photo))});
    const slug=slugify(d.name);
    try{await create(slug)}catch(e){
      // Slug/SKU already taken: retry once with a unique suffix.
      if(!(e instanceof ApiError&&e.status===409))throw e;
      await create(`${slug}-${crypto.randomUUID().slice(0,6)}`);
    }
    return 'Producto creado. Ya está en el catálogo.';
  }
  const saveStock=(p:AdminProduct,variantId:string,stock:number)=>run(async()=>{
    await api.patch(`/admin/products/${p.id}`,adminProductSchema,{variants:activeVariants(p).map(v=>variantBody({...v,stock:v.id===variantId?stock:v.stock},v.sku,v.imageUrl))});
    return 'Existencias actualizadas.';
  });

  return <div className="admin-shell"><aside className="admin-sidebar"><Link className="logo" href="/">FORMA.</Link><span className="admin-label">ESTUDIO / ADMIN</span><nav aria-label="Administración">{tabs.map(([id,label,Icon])=><Link key={id} href={`/admin?view=${id}`} className={tab===id?'active':''} onClick={()=>{setEditing(null);setNotice('');setError('')}}><Icon size={18}/>{label}</Link>)}</nav><div className="admin-sidebar-bottom"><span className="demo-pill">{adminEmail}</span><Link href="/">Visitar tienda <ArrowUpRight size={16}/></Link><a href="/account" onClick={async e=>{e.preventDefault();try{await logout()}finally{router.push('/account')}}}>Cerrar sesión</a></div></aside><main className="admin-main" id="main"><header className="admin-topbar"><span>Workspace / {title}</span><span className="admin-avatar">F.</span></header><div className="admin-content"><div className="admin-title"><div><p className="eyebrow">Tu tienda, de un vistazo</p><h1>{title}</h1></div>{tab==='products'&&<button className="button" disabled={!categories.length} onClick={()=>{setEditing(freshDraft());setNotice('');setError('')}}><Plus size={17}/> Crear producto</button>}</div><p className="admin-demo">Datos reales del catálogo y de los pedidos. Pagos de prueba con Mercado Pago. Sin envíos ni conexiones con proveedores.</p>{notice&&<p className="success-message" role="status">{notice}</p>}{error&&<p className="error" role="alert">{error} {error.includes('sesión')&&<Link href="/account?next=/admin">Iniciar sesión</Link>}</p>}

{tab==='overview'&&<><div className="metrics">{[['Productos',dashboard?.totalProducts],['Productos activos',dashboard?.activeProducts],['Pedidos registrados',dashboard?.registeredOrders],['Pendientes de pago',dashboard?.pendingOrders]].map(([label,value])=><div key={label}><span>{label}</span><strong>{value??'—'}</strong></div>)}</div><div className="admin-panel"><h2>Actividad reciente</h2>{orders.length?<div className="table-scroll"><table><thead><tr><th>Pedido</th><th>Cliente</th><th>Estado</th><th>Total</th></tr></thead><tbody>{orders.slice(0,5).map(o=><tr key={o.id}><td><Link href={`/account/orders/${o.id}`}>{o.orderNumber}</Link></td><td>{o.customerName}</td><td>{ORDER_STATUS[o.status]}</td><td>{money(o.total)}</td></tr>)}</tbody></table></div>:<div className="admin-empty"><ShoppingBag size={32}/><h3>Aún no hay pedidos.</h3><p>Los pedidos creados en el checkout aparecerán aquí.</p><Link className="text-link" href="/shop">Explorar tienda <ArrowUpRight size={16}/></Link></div>}</div><div className="admin-panel"><h2>Inventario por categoría</h2>{unitsByCategory.map(c=><div className="inventory-bar" key={c.name}><span>{c.name}</span><progress value={c.units} max={Math.max(1,...unitsByCategory.map(x=>x.units))}/><span>{c.units} uds.</span></div>)}</div></>}

{tab==='products'&&<>{editing&&<section className="admin-panel product-editor"><div className="drawer-heading"><h2>{editing.id?'Editar producto':'Nuevo producto'}</h2><button className="icon-button" aria-label="Cerrar editor" onClick={()=>setEditing(null)}><X/></button></div><form onSubmit={async e=>{e.preventDefault();if(await run(()=>saveDraft(editing)))setEditing(null)}}><div className="form-grid"><label>Nombre<input required maxLength={200} value={editing.name} onChange={e=>setEditing({...editing,name:e.target.value})}/></label><label>Categoría<select value={editing.categoryId} onChange={e=>setEditing({...editing,categoryId:e.target.value})}>{categories.map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label><label className="span-2">Descripción<textarea required rows={3} maxLength={5000} value={editing.description} onChange={e=>setEditing({...editing,description:e.target.value})}/></label><label>Foto de la biblioteca<select value={editing.photo} onChange={e=>setEditing({...editing,photo:e.target.value,photoChanged:true})}>{!PHOTO_LIBRARY.some(photo=>`/products/${photo}.jpg`===editing.photo)&&<option value={editing.photo}>actual</option>}{PHOTO_LIBRARY.map(photo=><option key={photo} value={`/products/${photo}.jpg`}>{photo}</option>)}</select></label>{editing.variants.map((v,i)=><fieldset className="variant-edit span-2" key={v.id||i}><legend>{v.name}</legend><label>Precio MXN<input type="number" min="1" step="1" required value={v.price} onChange={e=>setEditing({...editing,variants:editing.variants.map((x,j)=>j===i?{...x,price:Number(e.target.value)}:x)})}/></label><label>Existencias<input type="number" min="0" step="1" required value={v.stock} onChange={e=>setEditing({...editing,variants:editing.variants.map((x,j)=>j===i?{...x,stock:Number(e.target.value)}:x)})}/></label></fieldset>)}<label className="checkbox-label"><input type="checkbox" checked={editing.active} onChange={e=>setEditing({...editing,active:e.target.checked})}/> Activo</label><label className="checkbox-label"><input type="checkbox" checked={editing.featured} onChange={e=>setEditing({...editing,featured:e.target.checked})}/> Destacado en home</label></div><button className="button" disabled={busy}>{busy?'Guardando…':'Guardar producto'}</button></form></section>}<div className="admin-panel"><div className="admin-filters"><label className="sr-only" htmlFor="admin-search">Buscar productos</label><input id="admin-search" placeholder="Buscar producto…" value={query} onChange={e=>setQuery(e.target.value)}/><select aria-label="Filtrar por estado" value={status} onChange={e=>setStatus(e.target.value)}><option value="all">Todos los estados</option><option value="active">Activos</option><option value="inactive">Inactivos</option></select></div><div className="table-scroll"><table><thead><tr><th>Producto</th><th>Categoría</th><th>Precio</th><th>Estado</th><th>Acciones</th></tr></thead><tbody>{filtered.map(p=><tr key={p.id}><td><div className="table-product"><Image src={p.images[0]?.url||p.variants[0]?.imageUrl||FALLBACK_IMAGE} alt="" width={42} height={52}/>{p.status==='ACTIVE'?<Link href={`/product/${p.slug}`}>{p.name}</Link>:<span>{p.name}</span>}</div></td><td>{categoryName(p.categoryId)}</td><td>{money(p.price)}</td><td><button className={`status-button ${p.status==='ACTIVE'?'active':''}`} disabled={busy} onClick={()=>run(async()=>{const next=p.status==='ACTIVE'?'PAUSED':'ACTIVE';await api.patch(`/admin/products/${p.id}`,adminProductSchema,{status:next});return next==='ACTIVE'?'Producto activado: ya es visible en la tienda.':'Producto pausado: ya no aparece en la tienda.'})}>{PRODUCT_STATUS[p.status]}</button></td><td><div className="table-actions"><button className="icon-button" aria-label={`Editar ${p.name}`} onClick={()=>{setEditing(toDraft(p));setNotice('');setError('')}}><Pencil size={16}/></button>{deleting===p.id?<><button className="danger-text" disabled={busy} onClick={async()=>{await run(async()=>{const r=await api.delete(`/admin/products/${p.id}`,deleteResultSchema);return r.outcome==='ARCHIVED'?'El producto tiene pedidos: se archivó para conservar el historial.':'Producto eliminado del catálogo.'});setDeleting('')}}>Eliminar</button><button onClick={()=>setDeleting('')}>Cancelar</button></>:<button className="icon-button" aria-label={`Eliminar ${p.name}`} onClick={()=>setDeleting(p.id)}><Trash2 size={16}/></button>}</div></td></tr>)}</tbody></table>{!filtered.length&&<p className="admin-empty">No se encontraron productos.</p>}</div></div></>}

{tab==='payments'&&<AdminPayments/>}

{tab==='orders'&&<div className="admin-panel"><h2>Pedidos</h2>{orders.length?<div className="table-scroll"><table><thead><tr><th>Pedido</th><th>Cliente</th><th>Fecha</th><th>Total</th><th>Estado</th></tr></thead><tbody>{orders.map(o=><tr key={o.id}><td><Link className="text-link" href={`/account/orders/${o.id}`}>{o.orderNumber}<ArrowUpRight size={14}/></Link><small>Modo de pruebas</small></td><td>{o.customerName}<small>{o.email}</small></td><td>{formatDate(o.placedAt||o.createdAt)}</td><td>{money(o.total)}</td><td>{ORDER_STATUS[o.status]} {o.status==='PENDING_PAYMENT'&&<button className="danger-text" disabled={busy} aria-label={`Cancelar ${o.orderNumber}`} onClick={()=>run(async()=>{await api.post(`/admin/orders/${o.id}/cancel`,orderSchema,{note:'Cancelado desde administración'});return `Pedido ${o.orderNumber} cancelado. Sus existencias volvieron al inventario.`})}>Cancelar</button>}</td></tr>)}</tbody></table></div>:<p className="admin-empty">Todavía no hay pedidos registrados.</p>}<p className="muted">Los pagos se verifican con Mercado Pago. Solo se pueden cancelar pedidos que aún no iniciaron un pago; las preferencias activas conservan la reserva de inventario.</p></div>}

{tab==='suppliers'&&<div className="admin-panel"><h2>Directorio ilustrativo</h2><div className="table-scroll"><table><thead><tr><th>Proveedor ficticio</th><th>Ubicación</th><th>Categoría</th><th>Plazo de ejemplo</th></tr></thead><tbody>{suppliers.map(s=><tr key={s.id}><td>{s.name}</td><td>{s.location}</td><td>{s.category}</td><td>{s.leadTime}</td></tr>)}</tbody></table></div><p className="muted">Datos de demostración. No hay integraciones ni comunicaciones con estos proveedores.</p></div>}

{tab==='inventory'&&<div className="admin-panel"><h2>Existencias por variante</h2><div className="table-scroll"><table><thead><tr><th>Producto</th><th>Variante</th><th>Disponibilidad</th><th>Unidades</th></tr></thead><tbody>{products.filter(p=>p.status!=='ARCHIVED').flatMap(p=>activeVariants(p).map(v=><tr key={v.id}><td>{p.name}</td><td>{v.name}</td><td>{v.stock>0?'Disponible':'Agotado'}</td><td><input key={`${v.id}-${v.stock}`} className="stock-input" aria-label={`Existencias de ${p.name}, ${v.name}`} type="number" min="0" step="1" defaultValue={v.stock} disabled={busy} onKeyDown={e=>{if(e.key==='Enter')e.currentTarget.blur()}} onBlur={e=>{const n=Number(e.target.value);if(Number.isInteger(n)&&n>=0&&n!==v.stock)void saveStock(p,v.id,n);else e.target.value=String(v.stock)}}/></td></tr>))}</tbody></table></div><p className="muted">Los cambios se guardan al salir del campo o al pulsar Enter.</p></div>}

{tab==='settings'&&<div className="admin-panel settings-panel"><h2>Identidad de la tienda</h2><form onSubmit={e=>{e.preventDefault();setAnnouncement(String(new FormData(e.currentTarget).get('announcement')));setNotice('Texto del footer actualizado en este navegador.')}}><label>Mensaje del footer (preferencia local de este navegador)<input name="announcement" defaultValue={announcement} maxLength={100} required/></label><button className="button">Guardar configuración</button></form><h3>Paleta de marca</h3><div className="palette">{['#F7F6F2','#1B1B19','#E9E6E0','#565F46'].map(c=><div key={c}><span style={{background:c}}/>{c}</div>)}</div><p className="muted">FORMA. · Instrument Serif / Manrope</p></div>}
</div></main></div>;
}
