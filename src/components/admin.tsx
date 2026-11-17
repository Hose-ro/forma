'use client';
import { LIVE } from '@/lib/business';
import { Fulfillment } from './admin-fulfillment';
import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useSearchParams, useRouter } from 'next/navigation';
import { LayoutDashboard, Package, ShoppingBag, Truck, Boxes, Settings, ArrowUpRight, Plus, Pencil, Trash2, X, Tags } from 'lucide-react';
import { AdminPayments } from './admin-payments';
import { api } from '@/lib/api/client';
import { ApiError, errorMessage } from '@/lib/api/http';
import { adminProductPageSchema, adminProductSchema, categoriesSchema, categorySchema, dashboardSchema, deleteResultSchema, orderPageSchema, orderSchema } from '@/lib/api/schemas';
import { useShop } from '@/stores/shop';
import { FALLBACK_IMAGE, ORDER_STATUS, PHOTO_LIBRARY, formatDate, money, suppliers } from '@/lib/data';
import type { AdminProduct, Category, Dashboard, OrderSummary } from '@/types';

// Admin workspace backed by /api/v1/admin/* (ROLE_ADMIN enforced by the server on every call).
const tabs=[['overview','Resumen',LayoutDashboard],['products','Productos',Package],['categories','Categorías',Tags],['orders','Pedidos',ShoppingBag],['payments','Pagos',ShoppingBag],['suppliers','Proveedores',Truck],['inventory','Inventario',Boxes],['settings','Configuración',Settings]] as const;
const PRODUCT_STATUS:Record<AdminProduct['status'],string>={ACTIVE:'Activo',PAUSED:'Pausado',DRAFT:'Borrador',ARCHIVED:'Archivado'};
const PAGE_SIZE=25;
const IMAGE_HOSTS=(process.env.NEXT_PUBLIC_IMAGE_HOSTS||'').split(',').filter(Boolean);
type AdminVariant=AdminProduct['variants'][number];
type DraftVariant={key:string;id?:string;sku:string;name:string;color:string;price:number;stock:number;position:number;imageUrl?:string|null;compareAtPrice?:number|null;removed?:boolean};
type Draft={id?:string;original?:AdminProduct;name:string;categoryId:string;description:string;material:string;photos:string[];active:boolean;featured:boolean;variants:DraftVariant[]};
type Result={ok:boolean;status?:number};
type Run=(action:()=>Promise<string>)=>Promise<Result>;
type Paged<T>={items:T[];totalPages:number;totalElements:number};

/** Validation problem found before calling the API; its message is shown as is. */
class FormError extends Error{}

const slugify=(text:string)=>text.normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-+|-+$/g,'').slice(0,56)||'objeto';
const activeVariants=(p:AdminProduct)=>p.variants.filter(v=>v.active);
const thumb=(p:AdminProduct)=>p.images[0]?.url||p.variants[0]?.imageUrl||FALLBACK_IMAGE;
const newKey=()=>crypto.randomUUID();

/** Same rule next/image enforces on the storefront: site-relative paths or https on an allow-listed host. */
function photoProblem(url:string){
  if(/^\/(?!\/)\S*$/.test(url))return '';
  try{
    const u=new URL(url);
    if(u.protocol!=='https:')return 'La fotografía debe usar una dirección https://.';
    if(!IMAGE_HOSTS.includes(u.hostname))return `El dominio ${u.hostname} no está autorizado para imágenes. Agrégalo a IMAGE_ALLOWED_HOSTS en el despliegue de la tienda (hoy: ${IMAGE_HOSTS.join(', ')||'ninguno'}).`;
    return '';
  }catch{return 'Escribe una dirección completa (https://…) o una ruta del sitio (/products/archivo.jpg).'}
}

function adminFailure(e:unknown){
  if(e instanceof FormError)return e.message;
  if(e instanceof ApiError){
    if(e.status===401||e.status===403)return 'Tu sesión de administración expiró. Vuelve a iniciar sesión.';
    if(e.status===409&&/^Stock of/.test(e.detail))return 'Las existencias cambiaron mientras editabas (una venta u otro ajuste). Ya ves el dato actual: revísalo y vuelve a guardar.';
    if(e.status===409&&/provider|payment/i.test(e.detail))return 'El pedido tiene un pago abierto o en proceso con el procesador de pagos. Su inventario sigue reservado hasta que el pago se resuelva; inténtalo más tarde.';
    if(e.status===409&&/ship|fulfillment|deliver|correction|tracking/i.test(e.detail))return 'Ese cambio de logística no es válido: enviar requiere paquetería y guía, entregar requiere estar enviado, y para retroceder o cambiar la guía usa «Corregir».';
    if(e.status===409)return 'El cambio entra en conflicto con datos existentes (slug o SKU ya utilizados, o la categoría aún tiene productos).';
    if(e.status===422)return 'El cambio no cumple las reglas del catálogo (por ejemplo, un producto activo necesita una variante activa y el precio comparativo debe ser mayor al precio).';
    if(e.status===400)return 'Revisa los datos: hay campos con formato no válido.';
  }
  return errorMessage(e,'No pudimos completar la operación.');
}

function Pager({page,data,onChange,label}:{page:number;data:Paged<unknown>;onChange:(page:number)=>void;label:string}){
  if(data.totalElements<=data.items.length&&page===0)return <p className="muted">{data.totalElements} {label}</p>;
  return <nav className="pager" aria-label={`Páginas de ${label}`}><span>Página {page+1} de {Math.max(1,data.totalPages)} · {data.totalElements} {label}</span><div><button disabled={page===0} onClick={()=>onChange(page-1)}>Anterior</button><button disabled={page+1>=data.totalPages} onClick={()=>onChange(page+1)}>Siguiente</button></div></nav>;
}

export function Admin({adminEmail}:{adminEmail:string}){
  const {announcement,setAnnouncement,logout}=useShop();
  const params=useSearchParams();const router=useRouter();const tab=params.get('view')||'overview';
  const [products,setProducts]=useState<Paged<AdminProduct>|null>(null);
  const [orders,setOrders]=useState<Paged<OrderSummary>>({items:[],totalPages:0,totalElements:0});
  const [categories,setCategories]=useState<Category[]>([]);
  const [dashboard,setDashboard]=useState<Dashboard|null>(null);
  // Filters and pages are applied by the server, so nothing beyond the first page is ever hidden.
  const [productPage,setProductPage]=useState(0);const [productStatus,setProductStatus]=useState('');const [productQuery,setProductQuery]=useState('');const [search,setSearch]=useState('');
  const [orderPage,setOrderPage]=useState(0);const [orderStatus,setOrderStatus]=useState('');
  const [editing,setEditing]=useState<Draft|null>(null);
  const [notice,setNotice]=useState('');const [error,setError]=useState('');const [busy,setBusy]=useState(false);const [deleting,setDeleting]=useState('');

  const load=useCallback(async()=>{
    const productParams=new URLSearchParams({size:String(PAGE_SIZE),page:String(productPage)});
    if(productStatus)productParams.set('status',productStatus);
    if(productQuery)productParams.set('q',productQuery);
    const orderParams=new URLSearchParams({size:String(PAGE_SIZE),page:String(orderPage)});
    if(orderStatus)orderParams.set('status',orderStatus);
    try{
      const [p,o,d,c]=await Promise.all([api.get(`/admin/products?${productParams}`,adminProductPageSchema),api.get(`/admin/orders?${orderParams}`,orderPageSchema),api.get('/admin/dashboard',dashboardSchema),api.get('/admin/categories',categoriesSchema)]);
      setProducts(p);setOrders(o);setDashboard(d);setCategories(c);
    }catch(e){setProducts(current=>current??{items:[],totalPages:0,totalElements:0});setError(adminFailure(e))}
  },[productPage,productStatus,productQuery,orderPage,orderStatus]);
  useEffect(()=>{void load()},[load]);

  /** Runs an admin mutation, then reloads everything so the screen always shows server state. */
  const run:Run=async action=>{
    if(busy)return {ok:false};
    setBusy(true);setError('');setNotice('');
    try{setNotice(await action());await load();return {ok:true}}
    catch(e){
      setError(adminFailure(e));
      const status=e instanceof ApiError?e.status:undefined;
      // A conflict means the screen is stale: show what the server has now.
      if(status===409)await load();
      return {ok:false,status};
    }
    finally{setBusy(false)}
  };

  if(products===null)return <div className="page skeleton"/>;
  const categoryName=(id:string)=>categories.find(c=>c.id===id)?.name||'—';
  const unitsByCategory=dashboard?.unitsByCategory||[];
  const title=tabs.find(t=>t[0]===tab)?.[1]||'Resumen';
  const clear=()=>{setNotice('');setError('')};

  function freshDraft():Draft{return {name:'',categoryId:categories[0]?.id||'',description:'',material:'',photos:[],active:false,featured:false,variants:[{key:newKey(),sku:'',name:'Original',color:'#b6aa94',price:0,stock:0,position:0}]}}
  function toDraft(p:AdminProduct):Draft{return {id:p.id,original:p,name:p.name,categoryId:p.categoryId,description:p.description||'',material:p.material||'',photos:p.images.map(i=>i.url),active:p.status==='ACTIVE',featured:p.featured,variants:activeVariants(p).map(v=>({key:v.id,id:v.id,sku:v.sku,name:v.name,color:v.color||'#b6aa94',price:v.price,stock:v.stock,position:v.position,imageUrl:v.imageUrl,compareAtPrice:v.compareAtPrice}))}}

  async function saveDraft(d:Draft){
    const kept=d.variants.filter(v=>!v.removed);
    if(!kept.length)throw new FormError('El producto necesita al menos una variante.');
    if(!d.photos.length)throw new FormError('Añade al menos una fotografía del producto.');
    const o=d.original;
    const photosChanged=!o||JSON.stringify(d.photos)!==JSON.stringify(o.images.map(i=>i.url));
    const images=photosChanged?d.photos.map(url=>({url,altText:d.name.trim()})):undefined;
    const common={name:d.name.trim(),categoryId:d.categoryId,description:d.description,featured:d.featured,price:kept[0].price};
    const content=(v:DraftVariant|AdminVariant,imageUrl:string|null|undefined)=>({id:v.id,sku:v.sku.trim().toUpperCase(),name:v.name.trim(),color:v.color||undefined,price:v.price,compareAtPrice:v.compareAtPrice??undefined,position:v.position,imageUrl:imageUrl||undefined});
    if(d.id&&o){
      // Only what actually changed is sent. Stock travels with the value this form was opened with, so the
      // server rejects the save (409) if a sale happened in between instead of restoring sold units.
      const variants=d.variants.flatMap((v):Record<string,unknown>[]=>{
        const before=o.variants.find(x=>x.id===v.id);
        if(!before)return v.removed?[]:[{...content(v,d.photos[0]),stock:v.stock,active:true}];
        if(v.removed)return [{...content(before,before.imageUrl),active:false}];
        const image=photosChanged?d.photos[0]:before.imageUrl;
        const next=content(v,image);
        const contentChanged=JSON.stringify(next)!==JSON.stringify(content(before,before.imageUrl));
        const stockChanged=v.stock!==before.stock;
        if(!contentChanged&&!stockChanged)return [];
        return [{...next,...(stockChanged?{stock:v.stock,expectedStock:before.stock}:{})}];
      });
      // Keep the current status unless the "Activo" checkbox actually changed it.
      const status=d.active?'ACTIVE':o.status==='ACTIVE'?'PAUSED':o.status;
      await api.patch(`/admin/products/${d.id}`,adminProductSchema,{...common,material:d.material.trim(),status,images,variants:variants.length?variants:undefined});
      return 'Producto guardado. Los cambios ya aparecen en la tienda.';
    }
    const create=(slug:string)=>api.post('/admin/products',adminProductSchema,{...common,slug,material:d.material.trim()||undefined,status:d.active?'ACTIVE':'DRAFT',images,variants:kept.map((v,i)=>({...content({...v,sku:v.sku.trim()||`${slug}-${i+1}`,position:i},d.photos[0]),stock:v.stock,active:true}))});
    const slug=slugify(d.name);
    try{await create(slug)}catch(e){
      // Slug already taken: retry once with a unique suffix.
      if(!(e instanceof ApiError&&e.status===409))throw e;
      await create(`${slug}-${crypto.randomUUID().slice(0,6)}`);
    }
    return d.active?'Producto creado. Ya está en el catálogo.':'Producto creado como borrador. Actívalo cuando esté listo.';
  }
  async function submitDraft(d:Draft){
    const result=await run(()=>saveDraft(d));
    if(result.ok){setEditing(null);return}
    if(result.status!==409||!d.id)return;
    // Stale form: keep the administrator's text edits but restart from the stock the server has now.
    try{
      const fresh=await api.get(`/admin/products/${d.id}`,adminProductSchema);
      setEditing(current=>current&&current.id===fresh.id?{...current,original:fresh,variants:current.variants.map(v=>{const f=fresh.variants.find(x=>x.id===v.id);return f?{...v,stock:f.stock}:v})}:current);
    }catch{/* the list was already reloaded; the editor can be reopened */}
  }
  const saveStock=(v:AdminVariant,stock:number)=>run(async()=>{
    await api.post(`/admin/variants/${v.id}/stock`,adminProductSchema,{stock,expectedStock:v.stock});
    return 'Existencias actualizadas.';
  });

  return <div className="admin-shell"><aside className="admin-sidebar"><Link className="logo" href="/">OLVENIO.</Link><span className="admin-label">ESTUDIO / ADMIN</span><nav aria-label="Administración">{tabs.filter(t=>!LIVE||t[0]!=='suppliers').map(([id,label,Icon])=><Link key={id} href={`/admin?view=${id}`} className={tab===id?'active':''} onClick={()=>{setEditing(null);clear()}}><Icon size={18}/>{label}</Link>)}</nav><div className="admin-sidebar-bottom"><span className="demo-pill">{adminEmail}</span><Link href="/">Visitar tienda <ArrowUpRight size={16}/></Link><a href="/account" onClick={async e=>{e.preventDefault();try{await logout()}finally{router.push('/account')}}}>Cerrar sesión</a></div></aside><main className="admin-main" id="main"><header className="admin-topbar"><span>Workspace / {title}</span><span className="admin-avatar">O.</span></header><div className="admin-content"><div className="admin-title"><div><p className="eyebrow">Tu tienda, de un vistazo</p><h1>{title}</h1></div>{tab==='products'&&categories.length>0&&<button className="button" onClick={()=>{setEditing(freshDraft());clear()}}><Plus size={17}/> Crear producto</button>}</div><p className="admin-demo">{LIVE?'Tienda en operación: catálogo, pedidos y pagos reales verificados con cada procesador.':'Datos reales del catálogo y de los pedidos. Pagos de prueba. Sin envíos ni conexiones con proveedores.'}</p>{notice&&<p className="success-message" role="status">{notice}</p>}{error&&<p className="error" role="alert">{error} {error.includes('sesión')&&<Link href="/account?next=/admin">Iniciar sesión</Link>}</p>}

{tab==='overview'&&<><div className="metrics">{[['Productos',dashboard?.totalProducts],['Productos activos',dashboard?.activeProducts],['Pedidos registrados',dashboard?.registeredOrders],['Pendientes de pago',dashboard?.pendingOrders]].map(([label,value])=><div key={label}><span>{label}</span><strong>{value??'—'}</strong></div>)}</div>{!categories.length&&<div className="admin-panel"><div className="admin-empty"><Tags size={32}/><h3>Tu catálogo está vacío.</h3><p>Empieza por crear una categoría; después podrás añadir productos con sus fotografías, variantes y existencias.</p><Link className="text-link" href="/admin?view=categories">Crear la primera categoría <ArrowUpRight size={16}/></Link></div></div>}<div className="admin-panel"><h2>Actividad reciente</h2>{orders.items.length?<div className="table-scroll"><table><thead><tr><th>Pedido</th><th>Cliente</th><th>Estado</th><th>Total</th></tr></thead><tbody>{orders.items.slice(0,5).map(o=><tr key={o.id}><td><Link href={`/account/orders/${o.id}`}>{o.orderNumber}</Link></td><td>{o.customerName}</td><td>{ORDER_STATUS[o.status]}</td><td>{money(o.total)}</td></tr>)}</tbody></table></div>:<div className="admin-empty"><ShoppingBag size={32}/><h3>Aún no hay pedidos.</h3><p>Los pedidos creados en el checkout aparecerán aquí.</p><Link className="text-link" href="/shop">Explorar tienda <ArrowUpRight size={16}/></Link></div>}</div>{unitsByCategory.length>0&&<div className="admin-panel"><h2>Inventario por categoría</h2>{unitsByCategory.map(c=><div className="inventory-bar" key={c.name}><span>{c.name}</span><progress value={c.units} max={Math.max(1,...unitsByCategory.map(x=>x.units))}/><span>{c.units} uds.</span></div>)}</div>}</>}

{tab==='products'&&<>{!categories.length&&<div className="admin-panel"><div className="admin-empty"><Tags size={32}/><h3>Primero crea una categoría.</h3><p>Cada producto pertenece a una categoría de la tienda.</p><Link className="text-link" href="/admin?view=categories">Ir a categorías <ArrowUpRight size={16}/></Link></div></div>}{editing&&<ProductEditor key={editing.id||'new'} draft={editing} setDraft={setEditing} categories={categories} busy={busy} onSubmit={submitDraft}/>}<div className="admin-panel"><form className="admin-filters" role="search" onSubmit={e=>{e.preventDefault();setProductPage(0);setProductQuery(search.trim())}}><label className="sr-only" htmlFor="admin-search">Buscar productos</label><input id="admin-search" placeholder="Buscar producto y pulsar Enter…" maxLength={100} value={search} onChange={e=>setSearch(e.target.value)}/><select aria-label="Filtrar por estado" value={productStatus} onChange={e=>{setProductPage(0);setProductStatus(e.target.value)}}><option value="">Todos los estados</option>{Object.entries(PRODUCT_STATUS).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></form><div className="table-scroll"><table><thead><tr><th>Producto</th><th>Categoría</th><th>Precio</th><th>Estado</th><th>Acciones</th></tr></thead><tbody>{products.items.map(p=><tr key={p.id}><td><div className="table-product">{/* eslint-disable-next-line @next/next/no-img-element */}<img src={thumb(p)} alt="" width={42} height={52} loading="lazy"/>{p.status==='ACTIVE'?<Link href={`/product/${p.slug}`}>{p.name}</Link>:<span>{p.name}</span>}</div></td><td>{categoryName(p.categoryId)}</td><td>{money(p.price)}</td><td><button className={`status-button ${p.status==='ACTIVE'?'active':''}`} disabled={busy||p.status==='ARCHIVED'} onClick={()=>run(async()=>{const next=p.status==='ACTIVE'?'PAUSED':'ACTIVE';await api.patch(`/admin/products/${p.id}`,adminProductSchema,{status:next});return next==='ACTIVE'?'Producto activado: ya es visible en la tienda.':'Producto pausado: ya no aparece en la tienda.'})}>{PRODUCT_STATUS[p.status]}</button></td><td><div className="table-actions"><button className="icon-button" aria-label={`Editar ${p.name}`} onClick={()=>{setEditing(toDraft(p));clear()}}><Pencil size={16}/></button>{deleting===p.id?<><button className="danger-text" disabled={busy} onClick={async()=>{await run(async()=>{const r=await api.delete(`/admin/products/${p.id}`,deleteResultSchema);return r.outcome==='ARCHIVED'?'El producto tiene pedidos: se archivó para conservar el historial.':'Producto eliminado del catálogo.'});setDeleting('')}}>Eliminar</button><button onClick={()=>setDeleting('')}>Cancelar</button></>:<button className="icon-button" aria-label={`Eliminar ${p.name}`} onClick={()=>setDeleting(p.id)}><Trash2 size={16}/></button>}</div></td></tr>)}</tbody></table>{!products.items.length&&<p className="admin-empty">{productQuery||productStatus?'Ningún producto coincide con el filtro.':'Todavía no hay productos.'}</p>}</div><Pager page={productPage} data={products} onChange={setProductPage} label="productos"/></div></>}

{tab==='categories'&&<Categories categories={categories} busy={busy} run={run}/>}

{tab==='payments'&&<AdminPayments/>}

{tab==='orders'&&<div className="admin-panel"><h2>Pedidos</h2><div className="admin-filters"><select aria-label="Filtrar pedidos por estado" value={orderStatus} onChange={e=>{setOrderPage(0);setOrderStatus(e.target.value)}}><option value="">Todos los pedidos</option>{Object.entries(ORDER_STATUS).filter(([value])=>value!=='DRAFT').map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></div>{orders.items.length?<div className="table-scroll"><table><thead><tr><th>Pedido</th><th>Cliente</th><th>Fecha</th><th>Total</th><th>Estado</th><th>Envío</th></tr></thead><tbody>{orders.items.map(o=><tr key={o.id}><td><Link className="text-link" href={`/account/orders/${o.id}`}>{o.orderNumber}<ArrowUpRight size={14}/></Link>{!LIVE&&<small>Modo de pruebas</small>}</td><td>{o.customerName}<small>{o.email}</small></td><td>{formatDate(o.placedAt||o.createdAt)}</td><td>{money(o.total)}</td><td>{ORDER_STATUS[o.status]} {o.status==='PENDING_PAYMENT'&&<button className="danger-text" disabled={busy} aria-label={`Cancelar ${o.orderNumber}`} onClick={()=>run(async()=>{await api.post(`/admin/orders/${o.id}/cancel`,orderSchema,{note:'Cancelado desde administración'});return `Pedido ${o.orderNumber} cancelado. Sus existencias volvieron al inventario.`})}>Cancelar</button>}</td><td><Fulfillment order={o} busy={busy} run={run}/></td></tr>)}</tbody></table></div>:<p className="admin-empty">{orderStatus?'No hay pedidos en ese estado.':'Todavía no hay pedidos registrados.'}</p>}<Pager page={orderPage} data={orders} onChange={setOrderPage} label="pedidos"/><p className="muted">Los pagos se verifican directamente con cada procesador. Abre un pedido para ver la dirección de entrega; marcarlo como enviado exige paquetería y guía, y el cliente recibe un correo con esos datos. Un pedido pendiente conserva su inventario hasta que vence su reserva; entonces se cancela solo y las existencias regresan. Al cancelarlo aquí primero se cierran sus enlaces de pago en cada procesador, y no se libera inventario mientras exista un pago abierto o en proceso.</p></div>}

{tab==='suppliers'&&<div className="admin-panel"><h2>Directorio ilustrativo</h2><div className="table-scroll"><table><thead><tr><th>Proveedor ficticio</th><th>Ubicación</th><th>Categoría</th><th>Plazo de ejemplo</th></tr></thead><tbody>{suppliers.map(s=><tr key={s.id}><td>{s.name}</td><td>{s.location}</td><td>{s.category}</td><td>{s.leadTime}</td></tr>)}</tbody></table></div><p className="muted">Datos de demostración. No hay integraciones ni comunicaciones con estos proveedores.</p></div>}

{tab==='inventory'&&<div className="admin-panel"><h2>Existencias por variante</h2><div className="table-scroll"><table><thead><tr><th>Producto</th><th>Variante</th><th>SKU</th><th>Disponibilidad</th><th>Unidades</th></tr></thead><tbody>{products.items.filter(p=>p.status!=='ARCHIVED').flatMap(p=>activeVariants(p).map(v=><tr key={v.id}><td>{p.name}</td><td>{v.name}</td><td>{v.sku}</td><td>{v.stock>0?'Disponible':'Agotado'}</td><td><input key={`${v.id}-${v.stock}`} className="stock-input" aria-label={`Existencias de ${p.name}, ${v.name}`} type="number" min="0" step="1" defaultValue={v.stock} disabled={busy} onKeyDown={e=>{if(e.key==='Enter')e.currentTarget.blur()}} onBlur={e=>{const n=Number(e.target.value);if(e.target.value!==''&&Number.isInteger(n)&&n>=0&&n!==v.stock)void saveStock(v,n);else e.target.value=String(v.stock)}}/></td></tr>))}</tbody></table>{!products.items.length&&<p className="admin-empty">No hay productos en esta página.</p>}</div><Pager page={productPage} data={products} onChange={setProductPage} label="productos"/><p className="muted">Los cambios se guardan al salir del campo o al pulsar Enter. Si hubo una venta desde que cargó la página, el cambio se rechaza y verás la cifra actual. La lista usa el mismo filtro y página que Productos.</p></div>}

{tab==='settings'&&<div className="admin-panel settings-panel"><h2>Identidad de la tienda</h2><form onSubmit={e=>{e.preventDefault();setAnnouncement(String(new FormData(e.currentTarget).get('announcement')));setNotice('Texto del footer actualizado en este navegador.')}}><label>Mensaje del footer (preferencia local de este navegador)<input name="announcement" defaultValue={announcement} maxLength={100} required/></label><button className="button">Guardar configuración</button></form><h3>Paleta de marca</h3><div className="palette">{['#F7F6F2','#1B1B19','#E9E6E0','#565F46'].map(c=><div key={c}><span style={{background:c}}/>{c}</div>)}</div><p className="muted">Olvenio · Instrument Serif / Manrope</p></div>}
</div></main></div>;
}

function ProductEditor({draft:d,setDraft,categories,busy,onSubmit}:{draft:Draft;setDraft:(d:Draft|null)=>void;categories:Category[];busy:boolean;onSubmit:(d:Draft)=>Promise<void>}){
  const [photoUrl,setPhotoUrl]=useState('');const [photoError,setPhotoError]=useState('');
  const kept=d.variants.filter(v=>!v.removed);
  const setVariant=(key:string,patch:Partial<DraftVariant>)=>setDraft({...d,variants:d.variants.map(v=>v.key===key?{...v,...patch}:v)});
  function addPhoto(raw:string){
    const url=raw.trim();if(!url)return;
    const problem=d.photos.includes(url)?'Esa fotografía ya está en la lista.':url.length>500?'La dirección es demasiado larga (máximo 500 caracteres).':photoProblem(url);
    setPhotoError(problem);
    if(!problem){setDraft({...d,photos:[...d.photos,url]});setPhotoUrl('')}
  }
  return <section className="admin-panel product-editor"><div className="drawer-heading"><h2>{d.id?'Editar producto':'Nuevo producto'}</h2><button className="icon-button" aria-label="Cerrar editor" onClick={()=>setDraft(null)}><X/></button></div>
  <form onSubmit={e=>{e.preventDefault();void onSubmit(d)}}><div className="form-grid">
    <label>Nombre<input required maxLength={200} value={d.name} onChange={e=>setDraft({...d,name:e.target.value})}/></label>
    <label>Categoría<select required value={d.categoryId} onChange={e=>setDraft({...d,categoryId:e.target.value})}>{categories.map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
    <label className="span-2">Descripción<textarea required rows={3} maxLength={5000} value={d.description} onChange={e=>setDraft({...d,description:e.target.value})}/></label>
    <label className="span-2">Materiales y detalles<input maxLength={500} value={d.material} onChange={e=>setDraft({...d,material:e.target.value})}/></label>
    <div className="span-2"><span>Fotografías</span>
      <ul className="photo-list">{d.photos.map((url,i)=><li key={url}>{/* eslint-disable-next-line @next/next/no-img-element */}<img src={url} alt=""/><span>{url}{i===0&&' · principal'}</span><button type="button" className="danger-text" aria-label={`Quitar fotografía ${i+1}`} onClick={()=>setDraft({...d,photos:d.photos.filter(x=>x!==url)})}>Quitar</button></li>)}</ul>
      <div className="photo-add"><input aria-label="Dirección de la fotografía" inputMode="url" placeholder="https://… o /products/archivo.jpg" value={photoUrl} onChange={e=>setPhotoUrl(e.target.value)} onKeyDown={e=>{if(e.key==='Enter'){e.preventDefault();addPhoto(photoUrl)}}}/><button type="button" className="text-link" onClick={()=>addPhoto(photoUrl)}>Añadir fotografía</button>{!LIVE&&<select aria-label="Añadir desde la biblioteca de demostración" value="" onChange={e=>addPhoto(e.target.value)}><option value="">Biblioteca de demostración…</option>{PHOTO_LIBRARY.map(photo=><option key={photo} value={`/products/${photo}.jpg`}>{photo}</option>)}</select>}</div>
      {photoError&&<p className="error" role="alert">{photoError}</p>}
      <span className="field-hint">La primera es la principal. Usa la dirección pública de tus propias fotografías; dominios autorizados: {IMAGE_HOSTS.join(', ')||'ninguno'}.</span>
    </div>
    {kept.map(v=><fieldset className="variant-edit wide span-2" key={v.key}><legend>{v.name||'Variante'}</legend>
      <label>Nombre<input required maxLength={120} value={v.name} onChange={e=>setVariant(v.key,{name:e.target.value})}/></label>
      <label>SKU<input required={!!d.id} maxLength={64} pattern="[A-Za-z0-9_\-]{2,64}" placeholder={d.id?'':'Automático'} value={v.sku} onChange={e=>setVariant(v.key,{sku:e.target.value})}/></label>
      <label>Precio MXN<input type="number" min="1" step="0.01" required value={v.price} onChange={e=>setVariant(v.key,{price:Number(e.target.value)})}/></label>
      <label>Existencias<input type="number" min="0" step="1" required value={v.stock} onChange={e=>setVariant(v.key,{stock:Number(e.target.value)})}/></label>
      <div className="variant-actions"><label>Color <input type="color" value={v.color} onChange={e=>setVariant(v.key,{color:e.target.value})}/></label>{kept.length>1&&<button type="button" className="danger-text" onClick={()=>setVariant(v.key,{removed:true})}>{v.id?'Retirar variante':'Quitar variante'}</button>}</div>
    </fieldset>)}
    <div className="span-2"><button type="button" className="text-link" disabled={kept.length>=50} onClick={()=>setDraft({...d,variants:[...d.variants,{key:newKey(),sku:'',name:'',color:'#b6aa94',price:kept[0]?.price||0,stock:0,position:d.variants.length}]})}><Plus size={15}/> Añadir variante</button><span className="field-hint">Las existencias solo se envían si las cambias, junto con la cifra que viste al abrir el editor: si hubo una venta entretanto, el guardado se rechaza en lugar de reponer unidades vendidas. Una variante retirada deja de venderse pero se conserva en pedidos anteriores.</span></div>
    <label className="checkbox-label"><input type="checkbox" checked={d.active} onChange={e=>setDraft({...d,active:e.target.checked})}/> Activo (visible en la tienda)</label>
    <label className="checkbox-label"><input type="checkbox" checked={d.featured} onChange={e=>setDraft({...d,featured:e.target.checked})}/> Destacado en home</label>
  </div><button className="button" disabled={busy}>{busy?'Guardando…':'Guardar producto'}</button></form></section>;
}

type CategoryDraft={id?:string;name:string;slug:string;description:string;imageUrl:string;sortOrder:number};
function Categories({categories,busy,run}:{categories:Category[];busy:boolean;run:Run}){
  const blank:CategoryDraft={name:'',slug:'',description:'',imageUrl:'',sortOrder:categories.length};
  const [draft,setDraft]=useState<CategoryDraft|null>(categories.length?null:blank);
  const [deleting,setDeleting]=useState('');
  async function save(c:CategoryDraft){
    const imageUrl=c.imageUrl.trim();
    if(imageUrl){const problem=photoProblem(imageUrl);if(problem)throw new FormError(problem)}
    const body={name:c.name.trim(),slug:c.slug.trim()||slugify(c.name),description:c.description.trim()||undefined,imageUrl:imageUrl||undefined,sortOrder:c.sortOrder};
    if(c.id){await api.patch(`/admin/categories/${c.id}`,categorySchema,body);return 'Categoría guardada.'}
    await api.post('/admin/categories',categorySchema,body);
    return 'Categoría creada. Ya puedes asignarle productos.';
  }
  return <div className="admin-panel"><div className="drawer-heading"><h2>Categorías</h2>{!draft&&<button className="button" onClick={()=>setDraft(blank)}><Plus size={17}/> Nueva categoría</button>}</div>
    {draft&&<form className="category-form" onSubmit={async e=>{e.preventDefault();if((await run(()=>save(draft))).ok)setDraft(null)}}>
      <label>Nombre<input required maxLength={120} value={draft.name} onChange={e=>setDraft({...draft,name:e.target.value})}/></label>
      <label>Identificador en la dirección (slug)<input maxLength={120} pattern="[a-z0-9]+(-[a-z0-9]+)*" placeholder={slugify(draft.name||'categoria')} value={draft.slug} onChange={e=>setDraft({...draft,slug:e.target.value})}/></label>
      <label className="span-2">Descripción<input maxLength={500} value={draft.description} onChange={e=>setDraft({...draft,description:e.target.value})}/></label>
      <label>Imagen (dirección)<input inputMode="url" maxLength={500} placeholder="https://… o /products/archivo.jpg" value={draft.imageUrl} onChange={e=>setDraft({...draft,imageUrl:e.target.value})}/></label>
      <label>Orden<input type="number" step="1" required value={draft.sortOrder} onChange={e=>setDraft({...draft,sortOrder:Number(e.target.value)})}/></label>
      <div className="row-actions"><button className="button" disabled={busy}>{busy?'Guardando…':draft.id?'Guardar categoría':'Crear categoría'}</button>{categories.length>0&&<button type="button" className="text-link" onClick={()=>setDraft(null)}>Cancelar</button>}</div>
    </form>}
    {categories.length?<div className="table-scroll"><table><thead><tr><th>Categoría</th><th>Slug</th><th>Orden</th><th>Acciones</th></tr></thead><tbody>{categories.map(c=><tr key={c.id}><td>{c.name}{c.description&&<small>{c.description}</small>}</td><td>{c.slug}</td><td>{c.sortOrder}</td><td><div className="table-actions"><button className="icon-button" aria-label={`Editar ${c.name}`} onClick={()=>setDraft({id:c.id,name:c.name,slug:c.slug,description:c.description||'',imageUrl:c.imageUrl||'',sortOrder:c.sortOrder})}><Pencil size={16}/></button>{deleting===c.id?<><button className="danger-text" disabled={busy} onClick={async()=>{await run(async()=>{await api.delete(`/admin/categories/${c.id}`,null);return 'Categoría eliminada.'});setDeleting('')}}>Eliminar</button><button onClick={()=>setDeleting('')}>Cancelar</button></>:<button className="icon-button" aria-label={`Eliminar ${c.name}`} onClick={()=>setDeleting(c.id)}><Trash2 size={16}/></button>}</div></td></tr>)}</tbody></table></div>:<div className="admin-empty"><Tags size={32}/><h3>Aún no hay categorías.</h3><p>Crea la primera con el formulario de arriba; los productos se organizan dentro de ellas.</p></div>}
    <p className="muted">Una categoría solo puede eliminarse cuando ya no tiene productos.</p>
  </div>;
}
