'use client';

import Image from 'next/image';
import Link from 'next/link';
import { ArrowRight, ArrowUpRight, Gift, LayoutGrid, Lock, RefreshCw, Sparkles, Truck } from 'lucide-react';
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { FALLBACK_IMAGE, money } from '@/lib/data';
import {
  DEFAULT_HEROES, EDITORIAL, type HeroAContent, type HeroBContent, type HeroCampaignContent, type HeroMomentContent, type Moment,
} from '@/lib/home-heroes';
import type { Category, Product } from '@/types';
import { Reveal } from './ui';
import { ProductCard } from './product-card';
import { SocialLinks } from './social-links';

export type HeroCampaign = HeroCampaignContent & { product: Product };
export type HeroMoment = HeroMomentContent & { products: Product[] };
export type HomeHero =
  | { variant: 'a'; content: HeroAContent }
  | { variant: 'b'; content: HeroBContent }
  | { variant: 'c'; campaigns: HeroCampaign[] }
  | { variant: 'd'; title: string; italic?: string | null; moments: HeroMoment[]; initial: Moment };
export type WorldShelf = { category: Category; products: Product[]; total: number };
export type GiftShelf = { id: string; label: string; href: string; products: Product[] };

const shopCategory = (slug: string) => `/shop?category=${encodeURIComponent(slug)}`;
const pad = (n: number) => String(n).padStart(2, '0');
const COUNT_WORDS: Record<number, string> = { 2: 'Dos', 3: 'Tres', 4: 'Cuatro' };

export function Home({ hero, featured, categories, worlds, gifts, personalized }: {
  hero: HomeHero;
  featured: Product[];
  categories: Category[];
  worlds: WorldShelf[];
  gifts: GiftShelf[];
  personalized: boolean;
}) {
  const scene = useRef<HTMLDivElement>(null);
  useEffect(() => {
    gsap.registerPlugin(ScrollTrigger);
    const media = gsap.matchMedia();
    media.add('(min-width: 1024px) and (prefers-reduced-motion: no-preference)', () => {
      const root = scene.current;
      if (!root) return;
      const context = gsap.context(() => {
        if (root.querySelector('.hm-parallax')) {
          gsap.to('.hm-parallax', { yPercent: 12, ease: 'none',
            scrollTrigger: { trigger: '.hm-mosaic', start: 'top top', end: 'bottom top', scrub: 1 } });
        }
        gsap.fromTo('.editorial-photo', { yPercent: -5 }, { yPercent: 5, ease: 'none',
          scrollTrigger: { trigger: '.editorial', start: 'top bottom', end: 'bottom top', scrub: 1 } });
      }, root);
      return () => context.revert();
    });
    return () => media.revert();
  }, []);

  return <div ref={scene}>
    {hero.variant === 'b' && categories.length >= 2 ? <HeroB content={hero.content} categories={categories} />
      : hero.variant === 'c' ? <HeroC campaigns={hero.campaigns} />
      : hero.variant === 'd' ? <HeroD title={hero.title} italic={hero.italic} moments={hero.moments} initial={hero.initial} />
      : <HeroA content={hero.variant === 'a' ? hero.content : DEFAULT_HEROES.a} categories={categories} />}

    <WorldBar categories={categories} />

    <section className="section featured">
      <div className="section-heading">
        <Reveal>
          {personalized ? <h2>Selección para <i>ti.</i></h2> : <>
            <p className="eyebrow">La selección Olvenio</p>
            <h2>Extraordinariamente <i>cotidianos.</i></h2>
          </>}
        </Reveal>
        <Link className="text-link" href="/shop">Ver toda la tienda <ArrowUpRight size={18} /></Link>
      </div>
      <div className="product-grid">
        {featured.map((product, index) => <Reveal key={product.id} delay={index * .07}>
          <ProductCard product={product} />
        </Reveal>)}
      </div>
    </section>

    {worlds.length > 0 && <WorldsSection worlds={worlds} />}

    <section className="editorial">
      <Image className="editorial-photo" src="/products/editorial.jpg"
        alt="Interior sereno con muebles de madera, texturas suaves y luz natural" fill sizes="100vw" />
      <div className="editorial-shade" />
      <div className="editorial-copy"><Reveal>
        <span className="eyebrow">{EDITORIAL.eyebrow}</span>
        <h2>{EDITORIAL.title}<br />{EDITORIAL.titleEnd} <i>{EDITORIAL.italic}</i></h2>
        <p>{EDITORIAL.body}</p>
        <Link className="text-link" href={shopCategory(EDITORIAL.categorySlug)}>{EDITORIAL.cta} <ArrowRight size={19} /></Link>
      </Reveal></div>
      <span className="editorial-footnote">Una forma de mirar. Una forma de vivir.</span>
    </section>

    {gifts.length > 0 && <GiftsSection gifts={gifts} />}

    <TrustBar />
  </div>;
}

function Choices<T extends string>({ label, options, value, onChange, className = '' }: {
  label: string;
  options: { id: T; label: ReactNode }[];
  value: T;
  onChange: (id: T) => void;
  className?: string;
}) {
  return <div className={`hm-choices ${className}`} role="group" aria-label={label}>
    {options.map(option => <button key={option.id} type="button" aria-pressed={option.id === value}
      onClick={() => onChange(option.id)}>{option.label}</button>)}
  </div>;
}

function HeroA({ content, categories }: { content: HeroAContent; categories: Category[] }) {
  const { main, tiles } = content;
  const worldNames = categories.slice(0, 3).map(category => category.name).join(' · ');
  return <section className="hm-hero">
    <div className="hm-hero-meta">
      <span>{worldNames ? `${worldNames} — una sola mirada.` : 'Una sola mirada.'}</span>
      {content.meta && <span>{content.meta}</span>}
    </div>
    <div className="hm-hero-head">
      <h1>{content.title}{content.italic && <> <i>{content.italic}</i></>}</h1>
      <div className="hm-hero-note">
        {content.body && <p>{content.body}</p>}
        <Link className="button" href="/shop">{content.ctaLabel} <ArrowUpRight size={18} /></Link>
        <SocialLinks />
      </div>
    </div>
    <div className="hm-mosaic">
      <Link className="hm-tile hm-tile-main" href={shopCategory(main.categorySlug)}>
        <Image className="hm-parallax" src={main.imageUrl} alt={main.alt ?? ''} fill priority sizes="(max-width: 900px) 100vw, 64vw" />
        <span className="hm-tile-caption">
          <span><small>{main.label}</small><strong>{main.title}</strong></span>
          <span className="hm-tile-cta">Descubrir <ArrowUpRight size={16} /></span>
        </span>
      </Link>
      <div className="hm-mosaic-side">
        {tiles.map((tile, index) => <Link key={index} className="hm-tile" href={shopCategory(tile.categorySlug)}>
          <Image src={tile.imageUrl} alt={tile.alt ?? ''} fill sizes="(max-width: 900px) 50vw, 34vw" />
          <span className="hm-tile-caption"><span><small>{tile.label}</small><strong>{tile.title}</strong></span></span>
        </Link>)}
      </div>
    </div>
  </section>;
}

function HeroB({ content, categories }: { content: HeroBContent; categories: Category[] }) {
  const worlds = categories.slice(0, 4);
  return <section className="hm-hero">
    <div className="hm-hero-head hm-hero-head-spaced">
      <h1>{content.title || `${COUNT_WORDS[worlds.length]} mundos.`}{content.italic && <> <i>{content.italic}</i></>}</h1>
      <div className="hm-hero-aside">
        {content.body && <p>{content.body}</p>}
        <SocialLinks />
      </div>
    </div>
    <div className="hm-worlds-row" style={{ '--count': worlds.length } as CSSProperties}>
      {worlds.map((category, index) => <Link key={category.id} className="hm-tile" href={shopCategory(category.slug)}>
        <Image src={category.imageUrl || FALLBACK_IMAGE} alt="" fill priority={index === 0}
          sizes={`(max-width: 900px) 100vw, ${Math.round(100 / worlds.length)}vw`} />
        <span className="hm-tile-caption hm-tile-caption-stack">
          <small>{pad(index + 1)}</small>
          <strong className="hm-world-name">{category.name}</strong>
          {category.description && <span>{category.description}</span>}
          <span className="hm-tile-cta">Explorar {category.name} <ArrowUpRight size={16} /></span>
        </span>
      </Link>)}
    </div>
  </section>;
}

function HeroC({ campaigns }: { campaigns: HeroCampaign[] }) {
  const [id, setId] = useState('0');
  const campaign = campaigns[Number(id)] ?? campaigns[0];
  const href = `/product/${campaign.product.slug}`;
  return <section className="hm-hero">
    <div className="hm-campaign">
      <div className="hm-campaign-copy">
        <div>
          {campaign.eyebrow && <p className="eyebrow">{campaign.eyebrow}</p>}
          <h1>{campaign.title}{campaign.italic && <> <i>{campaign.italic}</i></>}</h1>
          {campaign.body && <p>{campaign.body}</p>}
        </div>
        <div className="hm-hero-side">
          <div className="hm-campaign-actions">
            <Link className="button" href={href}>Descubrir {campaign.product.name} <ArrowUpRight size={18} /></Link>
            <span>{money(campaign.product.price)} <small>MXN</small></span>
          </div>
          <SocialLinks />
        </div>
      </div>
      <div className="hm-campaign-image">
        <Image key={campaign.product.slug} src={campaign.product.images[0]} alt={campaign.product.name} fill priority
          sizes="(max-width: 900px) 100vw, 58vw" />
      </div>
    </div>
    {campaigns.length > 1 && <Choices className="hm-choices-rule" label="Elegir campaña" value={id} onChange={setId}
      options={campaigns.map((item, index) => ({ id: String(index), label: <>
        <small>{pad(index + 1)} / {item.world}</small><span>{item.product.name}</span>
      </> }))} />}
  </section>;
}

function HeroD({ title, italic, moments, initial }: {
  title: string; italic?: string | null; moments: HeroMoment[]; initial: Moment;
}) {
  const [id, setId] = useState<Moment>(initial);
  const moment = moments.find(item => item.id === id) ?? moments[0];
  return <section className="hm-hero">
    <div className="hm-hero-head hm-hero-head-spaced">
      <h1>{title}{italic && <> <i>{italic}</i></>}</h1>
      <div className="hm-hero-side">
        {moments.length > 1 && <Choices label="Elegir momento del día" value={moment.id} onChange={setId}
          options={moments.map(item => ({ id: item.id, label: item.label }))} />}
        <SocialLinks />
      </div>
    </div>
    <div className="hm-moment">
      <div className="hm-tile hm-moment-image">
        <Image key={moment.id} src={moment.imageUrl} alt={moment.alt ?? ''} fill priority sizes="(max-width: 900px) 100vw, 58vw" />
        <span className="hm-tile-caption"><span><small>{moment.label}</small><strong>{moment.line}</strong></span></span>
      </div>
      <ul className="hm-moment-list">
        {moment.products.map(product => <li key={product.id}>
          <Link className="hm-moment-item" href={`/product/${product.slug}`}>
            <span className="hm-moment-thumb"><Image src={product.images[0]} alt="" fill sizes="150px" /></span>
            <span>
              <small>{product.category}</small>
              <strong>{product.name}</strong>
              <span className="hm-price">{money(product.price)} MXN</span>
            </span>
          </Link>
        </li>)}
      </ul>
    </div>
  </section>;
}

function WorldBar({ categories }: { categories: Category[] }) {
  const intents = [
    { key: 'new', label: 'Novedades', href: '/shop?sort=newest', icon: <Sparkles size={28} strokeWidth={1.4} /> },
    { key: 'gifts', label: 'Para regalar', href: '#regalar', icon: <Gift size={28} strokeWidth={1.4} /> },
    { key: 'all', label: 'Toda la tienda', href: '/shop', icon: <LayoutGrid size={28} strokeWidth={1.4} /> },
  ];
  return <section className="hm-worldbar" aria-labelledby="hm-worldbar-title">
    <h2 id="hm-worldbar-title">Explora por mundo</h2>
    <ul>
      {categories.map(category => <li key={category.id}>
        <Link href={shopCategory(category.slug)}>
          <span className="hm-worldbar-media">
            <Image src={category.imageUrl || FALLBACK_IMAGE} alt="" fill sizes="96px" />
          </span>
          {category.name}
        </Link>
      </li>)}
      {intents.map(intent => <li key={intent.key}>
        <Link href={intent.href}><span className="hm-worldbar-media" aria-hidden="true">{intent.icon}</span>{intent.label}</Link>
      </li>)}
    </ul>
  </section>;
}

function WorldsSection({ worlds }: { worlds: WorldShelf[] }) {
  const [id, setId] = useState(worlds[0].category.id);
  const world = worlds.find(item => item.category.id === id) ?? worlds[0];
  const { category } = world;
  return <section className="section hm-shelf" id="mundos">
    <div className="section-heading">
      <Reveal><p className="eyebrow">Lo mejor de cada mundo</p><h2>Un mismo criterio, <i>muchas formas.</i></h2></Reveal>
      {worlds.length > 1 && <Choices label="Elegir mundo" value={world.category.id} onChange={setId}
        options={worlds.map(item => ({ id: item.category.id, label: item.category.name }))} />}
    </div>
    <div className="hm-world">
      <Link className="hm-tile hm-world-intro" href={shopCategory(category.slug)}>
        <Image key={category.id} src={category.imageUrl || FALLBACK_IMAGE} alt="" fill sizes="(max-width: 1200px) 100vw, 33vw" />
        <span className="hm-tile-caption hm-tile-caption-stack">
          <small>{world.total} {world.total === 1 ? 'producto' : 'productos'}</small>
          <strong className="hm-world-name">{category.name}</strong>
          {category.description && <span>{category.description}</span>}
          <span className="hm-tile-cta">Ver todo {category.name} <ArrowUpRight size={16} /></span>
        </span>
      </Link>
      <div className="product-grid hm-world-grid">
        {world.products.map(product => <ProductCard key={product.id} product={product} />)}
      </div>
    </div>
  </section>;
}

function GiftsSection({ gifts }: { gifts: GiftShelf[] }) {
  const [id, setId] = useState(gifts[0].id);
  const shelf = gifts.find(item => item.id === id) ?? gifts[0];
  return <section className="section hm-shelf hm-gifts" id="regalar">
    <div className="section-heading">
      <Reveal><p className="eyebrow">Para regalar</p><h2>Un detalle <i>para cada quien.</i></h2></Reveal>
      {gifts.length > 1 && <Choices label="Rango de precio" value={shelf.id} onChange={setId}
        options={gifts.map(item => ({ id: item.id, label: item.label }))} />}
    </div>
    <div className="product-grid">
      {shelf.products.map(product => <ProductCard key={product.id} product={product} />)}
    </div>
    <Link className="text-link hm-shelf-more" href={shelf.href}>Ver todo en {shelf.label.toLowerCase()} <ArrowUpRight size={18} /></Link>
  </section>;
}

function TrustBar() {
  return <section className="hm-trust" aria-label="Compra con confianza">
    <div><Lock size={26} strokeWidth={1.4} aria-hidden="true" />
      <p><strong>Pago seguro</strong>El cobro lo procesa nuestro proveedor de pagos; no guardamos datos de tarjeta.</p></div>
    <div><Truck size={26} strokeWidth={1.4} aria-hidden="true" />
      <p><strong>Envío gratis</strong>Sigue tu pedido desde tu cuenta o con tu número de orden.</p></div>
    <div><RefreshCw size={26} strokeWidth={1.4} aria-hidden="true" />
      <p><strong>Cambios y devoluciones</strong>Consulta nuestra <Link href="/policies/shipping">política de envíos y devoluciones</Link>.</p></div>
  </section>;
}
