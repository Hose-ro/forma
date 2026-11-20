'use client';

import Image from 'next/image';
import Link from 'next/link';
import { ArrowUpRight, ArrowDown, ArrowRight } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { FALLBACK_IMAGE } from '@/lib/data';
import type { Category, Product } from '@/types';
import { Reveal } from './ui';
import { ProductCard } from './product-card';

export function Home({ featured, categories, personalized }: {
  featured: Product[];
  categories: Category[];
  personalized: boolean;
}) {
  const scene = useRef<HTMLDivElement>(null);
  useEffect(() => {
    gsap.registerPlugin(ScrollTrigger);
    const media = gsap.matchMedia();
    media.add('(min-width: 1024px) and (prefers-reduced-motion: no-preference)', () => {
      const context = gsap.context(() => {
        gsap.to('.hero-photo', { yPercent: 12, ease: 'none',
          scrollTrigger: { trigger: scene.current, start: 'top top', end: 'bottom top', scrub: 1 } });
        gsap.fromTo('.editorial-photo', { yPercent: -5 }, { yPercent: 5, ease: 'none',
          scrollTrigger: { trigger: '.editorial', start: 'top bottom', end: 'bottom top', scrub: 1 } });
      }, scene);
      return () => context.revert();
    });
    return () => media.revert();
  }, []);

  const featuredSection = <section className="section featured">
    <div className="section-heading">
      <Reveal>
        {personalized ? <h2>Selección para <i>ti.</i></h2> : <>
          <p className="eyebrow">La selección Olvenio</p>
          <h2>Extraordinariamente <i>cotidianos.</i></h2>
        </>}
      </Reveal>
      <Link className="text-link" href="/shop">Ver toda la colección <ArrowUpRight size={18} /></Link>
    </div>
    <div className="product-grid">
      {featured.map((product, index) => <Reveal key={product.id} delay={index * .07}>
        <ProductCard product={product} />
      </Reveal>)}
    </div>
  </section>;

  return <div ref={scene}>
    <section className="hero">
      <div className="hero-meta"><span>Una nueva forma de vivir lo cotidiano.</span><span>La colección / 001</span></div>
      <div className="hero-title">
        <h1><span>Objects with</span><span><i>character.</i></span></h1>
        <div className="hero-note">
          <span className="little-cross">✳</span>
          <p>Diseño que se siente.<br />Objetos que se quedan.</p>
          <Link className="text-link" href="/shop">Explorar colección <ArrowUpRight size={20} /></Link>
        </div>
      </div>
      <div className="hero-image-wrap">
        <Image className="hero-photo" src="/products/hero.jpg" alt="Sala cálida con piezas de diseño, materiales naturales y luz de la tarde"
          fill priority sizes="100vw" />
        <div className="hero-image-caption">
          <span>01 / EL ARTE DE HABITAR</span>
          <Link href="/shop?category=Living">Descubrir Living <ArrowUpRight size={18} /></Link>
        </div>
      </div>
      <div className="hero-bottom">
        <span>Menos ruido. Más intención.</span>
        <a href="#categories" aria-label="Descubrir categorías">Desliza para descubrir <ArrowDown size={15} /></a>
      </div>
    </section>
    {personalized && featuredSection}
    <section className="section categories-section" id="categories">
      <div className="section-heading">
        <Reveal><p className="eyebrow">Explorar categorías</p><h2>Encuentra tu <i>forma.</i></h2></Reveal>
        <p>Una selección para escuchar,<br />habitar y llevar contigo.</p>
      </div>
      <div className="category-grid">
        {categories.map((category, index) => <Reveal key={category.id} delay={(index % 3) * .08}
          className={`category category-${index % 3}`}>
          <Link href={`/shop?category=${encodeURIComponent(category.slug)}`}>
            <div className="category-image">
              <Image src={category.imageUrl || FALLBACK_IMAGE} alt={category.description || category.name}
                fill sizes="(max-width: 640px) 90vw, 40vw" />
              <span className="category-index">{String(index + 1).padStart(2, '0')}</span>
              <span className="circle-arrow"><ArrowUpRight size={22} /></span>
            </div>
            <div className="category-caption"><h3>{category.name}</h3><p>{category.description}</p></div>
          </Link>
        </Reveal>)}
      </div>
    </section>
    {!personalized && featuredSection}
    <section className="editorial">
      <Image className="editorial-photo" src="/products/editorial.jpg"
        alt="Interior sereno con muebles de madera, texturas suaves y luz natural" fill sizes="100vw" />
      <div className="editorial-shade" />
      <div className="editorial-copy"><Reveal>
        <span className="eyebrow">Perspectivas / 01</span>
        <h2>El lujo de<br />lo <i>simple.</i></h2>
        <p>Hay objetos que no necesitan llamar la atención.<br />Solo estar en el lugar correcto.</p>
        <Link className="text-link" href="/shop?category=Living">Habita a tu manera <ArrowRight size={19} /></Link>
      </Reveal></div>
      <span className="editorial-footnote">Una forma de mirar. Una forma de vivir.</span>
    </section>
  </div>;
}
