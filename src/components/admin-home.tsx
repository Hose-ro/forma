'use client';
import { useEffect, useState, type ReactNode } from 'react';
import { ArrowUpRight, Plus, Trash2 } from 'lucide-react';
import { api } from '@/lib/api/client';
import { ApiError, errorMessage } from '@/lib/api/http';
import { adminProductPageSchema, heroContentSchemas, heroViewSchema, heroViewsSchema } from '@/lib/api/schemas';
import { PHOTO_LIBRARY, formatDate } from '@/lib/data';
import {
  DEFAULT_HEROES, HERO_NAMES, HERO_VARIANTS, MOMENTS, heroForTime, nextSlotHour,
  type HeroCampaignContent, type HeroContents, type HeroMomentContent, type HeroTile, type HeroVariant,
} from '@/lib/home-heroes';
import { photoProblem } from '@/lib/photo-url';
import type { Category } from '@/types';

// Home hero editor backed by /api/v1/admin/home/heroes. The server validates every field and checks that
// linked categories and products exist; the storefront rotates the enabled heroes every 12 hours.

type Entry<V extends HeroVariant> = { enabled: boolean; content: HeroContents[V]; updatedAt?: string | null };
type Entries = { [V in HeroVariant]: Entry<V> };
type ProductOption = { slug: string; name: string };

const IMAGE_OPTIONS = ['/products/hero.jpg', '/products/editorial.jpg', ...PHOTO_LIBRARY.map(name => `/products/${name}.jpg`)];
const blankCampaign: HeroCampaignContent = { world: '', eyebrow: '', title: '', italic: '', body: '', productSlug: '' };

function heroFailure(e: unknown) {
  if (e instanceof ApiError) {
    if (e.status === 401 || e.status === 403) return 'Tu sesión de administración expiró. Vuelve a iniciar sesión.';
    const product = e.detail.match(/^Unknown or inactive product: (.+)$/);
    if (product) return `No hay un producto activo con el slug «${product[1]}». Revisa el slug o activa el producto.`;
    const category = e.detail.match(/^Unknown category: (.+)$/);
    if (category) return `No existe la categoría «${category[1]}».`;
    if (/At least one hero/.test(e.detail)) return 'Debe quedar al menos un hero activo en la rotación.';
    if (/appears twice/.test(e.detail)) return 'Cada momento del día solo puede aparecer una vez.';
    if (e.status === 400) return 'Revisa los campos: hay datos vacíos, demasiado largos o con formato no válido.';
  }
  return errorMessage(e, 'No pudimos guardar el hero.');
}

/** Drops blank optional texts so the API stores null instead of empty strings. */
function clean<T>(value: T): T {
  if (Array.isArray(value)) return value.map(clean) as T;
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, typeof v === 'string' ? (v.trim() || null) : clean(v)])) as T;
  }
  return value;
}

function prepare(variant: HeroVariant, content: HeroContents[HeroVariant]) {
  if (variant !== 'd') return clean(content);
  const d = content as HeroContents['d'];
  return clean({ ...d, moments: d.moments.map(m => ({ ...m, productSlugs: m.productSlugs.filter(Boolean) })) });
}

function toEntries(rows: { variant: HeroVariant; enabled: boolean; content: unknown; updatedAt?: string | null }[]): Entries {
  const entry = <V extends HeroVariant>(variant: V): Entry<V> => {
    const row = rows.find(r => r.variant === variant);
    const parsed = heroContentSchemas[variant].safeParse(row?.content);
    return { enabled: row?.enabled ?? false, updatedAt: row?.updatedAt,
      content: (parsed.success ? parsed.data : DEFAULT_HEROES[variant]) as HeroContents[V] };
  };
  return { a: entry('a'), b: entry('b'), c: entry('c'), d: entry('d') };
}

export function AdminHome({ categories }: { categories: Category[] }) {
  const [entries, setEntries] = useState<Entries | null>(null);
  const [published, setPublished] = useState<HeroVariant[]>([]);
  const [products, setProducts] = useState<ProductOption[]>([]);
  const [loadError, setLoadError] = useState('');

  useEffect(() => {
    let alive = true;
    Promise.all([
      api.get('/admin/home/heroes', heroViewsSchema),
      api.get('/admin/products?status=ACTIVE&size=100', adminProductPageSchema).catch(() => null),
    ]).then(([rows, page]) => {
      if (!alive) return;
      setEntries(toEntries(rows));
      setPublished(rows.filter(r => r.enabled).map(r => r.variant));
      setProducts(page?.items.map(p => ({ slug: p.slug, name: p.name })) ?? []);
    }).catch(e => alive && setLoadError(heroFailure(e)));
    return () => { alive = false; };
  }, []);

  if (loadError) return <div className="admin-panel"><h2>Portada</h2><p className="error" role="alert">{loadError}</p></div>;
  if (!entries) return <div className="admin-panel"><h2>Portada</h2><p className="muted">Cargando heros…</p></div>;

  const now = new Date();
  const current = heroForTime(now, undefined, published);
  const change = nextSlotHour(now);

  function onSaved(variant: HeroVariant, enabled: boolean) {
    setPublished(list => HERO_VARIANTS.filter(v => v === variant ? enabled : list.includes(v)));
  }

  return <>
    <div className="admin-panel hero-status">
      <h2>Portada</h2>
      <p>Los heros activos se alternan cada 12 horas, a las 00:00 y a las 12:00 (hora de Ciudad de México), en orden A → B → C → D.</p>
      <p><strong>Ahora se muestra: {current.toUpperCase()} · {HERO_NAMES[current]}.</strong> Próximo cambio a las {change === 0 ? '00:00' : '12:00'}.
        {' '}En rotación: {published.length ? published.map(v => v.toUpperCase()).join(', ') : 'ninguno'}.</p>
    </div>
    <datalist id="hero-images">{IMAGE_OPTIONS.map(url => <option key={url} value={url} />)}</datalist>
    <datalist id="hero-products">{products.map(p => <option key={p.slug} value={p.slug}>{p.name}</option>)}</datalist>
    {HERO_VARIANTS.map(variant => <HeroCard key={variant} variant={variant} entries={entries} setEntries={setEntries}
      categories={categories} live={published.includes(variant)} onSaved={onSaved} />)}
  </>;
}

function HeroCard<V extends HeroVariant>({ variant, entries, setEntries, categories, live, onSaved }: {
  variant: V; entries: Entries; setEntries: (update: (e: Entries | null) => Entries | null) => void;
  categories: Category[]; live: boolean; onSaved: (variant: HeroVariant, enabled: boolean) => void;
}) {
  const entry = entries[variant] as Entry<V>;
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const update = (next: Partial<Entry<V>>) => setEntries(all => all && { ...all, [variant]: { ...all[variant], ...next } });
  const setContent = (content: HeroContents[V]) => update({ content });

  async function save() {
    const images = imagesOf(variant, entry.content);
    const problem = images.map(photoProblem).find(Boolean);
    if (problem) { setMessage({ ok: false, text: problem }); return; }
    setBusy(true); setMessage(null);
    try {
      const saved = await api.put(`/admin/home/heroes/${variant}`, heroViewSchema, { enabled: entry.enabled, content: prepare(variant, entry.content) });
      update({ updatedAt: saved.updatedAt });
      onSaved(variant, saved.enabled);
      setMessage({ ok: true, text: saved.enabled ? 'Guardado. Ya forma parte de la rotación.' : 'Guardado. Queda fuera de la rotación.' });
    } catch (e) {
      setMessage({ ok: false, text: heroFailure(e) });
    } finally { setBusy(false); }
  }

  return <section className="admin-panel hero-editor" aria-labelledby={`hero-${variant}`}>
    <div className="hero-editor-head">
      <h2 id={`hero-${variant}`}>Hero {variant.toUpperCase()} · {HERO_NAMES[variant]}</h2>
      {live && <a className="text-link" href={`/?hero=${variant}`} target="_blank" rel="noreferrer">Ver en la tienda <ArrowUpRight size={15} /></a>}
    </div>
    <form className="category-form" onSubmit={e => { e.preventDefault(); void save(); }}>
      <label className="checkbox-label span-2"><input type="checkbox" checked={entry.enabled}
        onChange={e => update({ enabled: e.target.checked })} /> Incluir en la rotación de la portada</label>
      {variant === 'a' && <HeroAFields content={entry.content as HeroContents['a']} set={c => setContent(c as HeroContents[V])} categories={categories} />}
      {variant === 'b' && <HeroBFields content={entry.content as HeroContents['b']} set={c => setContent(c as HeroContents[V])} />}
      {variant === 'c' && <HeroCFields content={entry.content as HeroContents['c']} set={c => setContent(c as HeroContents[V])} />}
      {variant === 'd' && <HeroDFields content={entry.content as HeroContents['d']} set={c => setContent(c as HeroContents[V])} />}
      <div className="row-actions">
        <button className="button" disabled={busy}>{busy ? 'Guardando…' : `Guardar hero ${variant.toUpperCase()}`}</button>
        {entry.updatedAt && <span className="muted">Última edición: {formatDate(entry.updatedAt)}</span>}
      </div>
      {message && <p className={`span-2 ${message.ok ? 'success-message' : 'error'}`} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
    </form>
  </section>;
}

function imagesOf(variant: HeroVariant, content: HeroContents[HeroVariant]): string[] {
  if (variant === 'a') { const a = content as HeroContents['a']; return [a.main, ...a.tiles].map(t => t.imageUrl); }
  if (variant === 'd') return (content as HeroContents['d']).moments.map(m => m.imageUrl);
  return [];
}

function Text({ label, value, onChange, max, required, wide, hint, list, multiline }: {
  label: string; value: string | null | undefined; onChange: (value: string) => void; max: number;
  required?: boolean; wide?: boolean; hint?: ReactNode; list?: string; multiline?: boolean;
}) {
  return <label className={wide ? 'span-2' : undefined}>{label}{required ? ' *' : ''}
    {multiline
      ? <textarea value={value ?? ''} maxLength={max} required={required} rows={2} onChange={e => onChange(e.target.value)} />
      : <input value={value ?? ''} maxLength={max} required={required} list={list} onChange={e => onChange(e.target.value)} />}
    {hint && <span className="field-hint">{hint}</span>}
  </label>;
}

function TileFields({ legend, tile, set, categories }: { legend: string; tile: HeroTile; set: (t: HeroTile) => void; categories: Category[] }) {
  const problem = tile.imageUrl ? photoProblem(tile.imageUrl) : '';
  return <fieldset className="span-2 hero-fieldset"><legend>{legend}</legend><div className="category-form">
    <Text label="Etiqueta" value={tile.label} max={40} required onChange={label => set({ ...tile, label })} hint="Ej.: 01 / Hogar" />
    <Text label="Frase" value={tile.title} max={80} required onChange={title => set({ ...tile, title })} />
    <Text label="Imagen" value={tile.imageUrl} max={500} required list="hero-images" onChange={imageUrl => set({ ...tile, imageUrl })}
      hint={problem || 'Ruta del sitio (/products/…) o https:// de un dominio autorizado.'} />
    <Text label="Descripción de la imagen" value={tile.alt} max={160} onChange={alt => set({ ...tile, alt })} hint="Para lectores de pantalla." />
    <label className="span-2">Enlaza a la categoría *
      <select value={tile.categorySlug} required onChange={e => set({ ...tile, categorySlug: e.target.value })}>
        {!categories.some(c => c.slug === tile.categorySlug) && <option value={tile.categorySlug}>{tile.categorySlug || 'Elige una categoría'}</option>}
        {categories.map(c => <option key={c.id} value={c.slug}>{c.name}</option>)}
      </select>
    </label>
  </div></fieldset>;
}

function HeroAFields({ content: a, set, categories }: { content: HeroContents['a']; set: (a: HeroContents['a']) => void; categories: Category[] }) {
  return <>
    <Text label="Titular" value={a.title} max={80} required onChange={title => set({ ...a, title })} />
    <Text label="Titular en cursiva" value={a.italic} max={80} onChange={italic => set({ ...a, italic })} />
    <Text label="Texto" value={a.body} max={240} wide multiline onChange={body => set({ ...a, body })} />
    <Text label="Botón" value={a.ctaLabel} max={40} required onChange={ctaLabel => set({ ...a, ctaLabel })} />
    <Text label="Nota superior" value={a.meta} max={60} onChange={meta => set({ ...a, meta })} hint="Ej.: Temporada / 01" />
    <TileFields legend="Imagen grande" tile={a.main} categories={categories} set={main => set({ ...a, main })} />
    {a.tiles.map((tile, index) => <TileFields key={index} legend={`Mosaico ${index + 1}`} tile={tile} categories={categories}
      set={next => set({ ...a, tiles: a.tiles.map((t, i) => i === index ? next : t) })} />)}
  </>;
}

function HeroBFields({ content: b, set }: { content: HeroContents['b']; set: (b: HeroContents['b']) => void }) {
  return <>
    <Text label="Titular" value={b.title} max={80} onChange={title => set({ ...b, title })}
      hint="Déjalo vacío para mostrar «Tres mundos.» según cuántas categorías tengas." />
    <Text label="Titular en cursiva" value={b.italic} max={80} onChange={italic => set({ ...b, italic })} />
    <Text label="Texto" value={b.body} max={240} wide multiline onChange={body => set({ ...b, body })} />
    <p className="span-2 muted">Las columnas salen de tus categorías (las primeras 4), con su imagen y descripción.</p>
  </>;
}

function HeroCFields({ content: c, set }: { content: HeroContents['c']; set: (c: HeroContents['c']) => void }) {
  const change = (index: number, next: HeroCampaignContent) => set({ campaigns: c.campaigns.map((x, i) => i === index ? next : x) });
  return <>
    {c.campaigns.map((campaign, index) => <fieldset key={index} className="span-2 hero-fieldset">
      <legend>Campaña {index + 1}</legend>
      <div className="category-form">
        <Text label="Pestaña" value={campaign.world} max={40} required onChange={world => change(index, { ...campaign, world })} hint="Ej.: Audio" />
        <Text label="Antetítulo" value={campaign.eyebrow} max={60} onChange={eyebrow => change(index, { ...campaign, eyebrow })} />
        <Text label="Titular" value={campaign.title} max={60} required onChange={title => change(index, { ...campaign, title })} />
        <Text label="Titular en cursiva" value={campaign.italic} max={60} onChange={italic => change(index, { ...campaign, italic })} />
        <Text label="Texto" value={campaign.body} max={240} wide multiline onChange={body => change(index, { ...campaign, body })} />
        <Text label="Producto (slug)" value={campaign.productSlug} max={160} required wide list="hero-products"
          onChange={productSlug => change(index, { ...campaign, productSlug: productSlug.trim() })}
          hint="Debe ser un producto activo; la foto y el precio salen de su ficha." />
        {c.campaigns.length > 1 && <div className="row-actions"><button type="button" className="text-link"
          onClick={() => set({ campaigns: c.campaigns.filter((_, i) => i !== index) })}><Trash2 size={15} /> Quitar campaña</button></div>}
      </div>
    </fieldset>)}
    {c.campaigns.length < 4 && <div className="row-actions"><button type="button" className="text-link"
      onClick={() => set({ campaigns: [...c.campaigns, blankCampaign] })}><Plus size={15} /> Agregar campaña</button></div>}
  </>;
}

function HeroDFields({ content: d, set }: { content: HeroContents['d']; set: (d: HeroContents['d']) => void }) {
  const byId = new Map(d.moments.map(m => [m.id, m]));
  const toggle = (id: HeroMomentContent['id'], on: boolean) => {
    const label = MOMENTS.find(m => m.id === id)!.label;
    const next = on ? [...d.moments, { id, label, line: '', imageUrl: '', alt: '', productSlugs: [] }] : d.moments.filter(m => m.id !== id);
    set({ ...d, moments: MOMENTS.flatMap(m => next.find(x => x.id === m.id) ?? []) });
  };
  const change = (next: HeroMomentContent) => set({ ...d, moments: d.moments.map(m => m.id === next.id ? next : m) });
  return <>
    <Text label="Titular" value={d.title} max={60} required onChange={title => set({ ...d, title })} />
    <Text label="Titular en cursiva" value={d.italic} max={60} onChange={italic => set({ ...d, italic })} />
    {MOMENTS.map(({ id, label }) => {
      const moment = byId.get(id);
      return <fieldset key={id} className="span-2 hero-fieldset">
        <legend><label className="checkbox-label"><input type="checkbox" checked={!!moment}
          disabled={!!moment && d.moments.length === 1} onChange={e => toggle(id, e.target.checked)} /> {label}</label></legend>
        {moment && <div className="category-form">
          <Text label="Nombre de la pestaña" value={moment.label} max={30} required onChange={l => change({ ...moment, label: l })} />
          <Text label="Frase" value={moment.line} max={80} required onChange={line => change({ ...moment, line })} />
          <Text label="Imagen" value={moment.imageUrl} max={500} required list="hero-images" onChange={imageUrl => change({ ...moment, imageUrl })}
            hint={(moment.imageUrl && photoProblem(moment.imageUrl)) || 'Ruta del sitio (/products/…) o https:// de un dominio autorizado.'} />
          <Text label="Descripción de la imagen" value={moment.alt} max={160} onChange={alt => change({ ...moment, alt })} />
          {[0, 1, 2].map(slot => <Text key={slot} label={`Producto ${slot + 1} (slug)`} value={moment.productSlugs[slot] ?? ''} max={160}
            required={slot === 0} list="hero-products"
            onChange={value => {
              // Empty slots stay in place while editing; they are dropped when saving.
              change({ ...moment, productSlugs: [0, 1, 2].map(i => i === slot ? value.trim() : moment.productSlugs[i] ?? '') });
            }} />)}
        </div>}
      </fieldset>;
    })}
  </>;
}
