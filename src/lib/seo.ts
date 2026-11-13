import type { Metadata } from 'next';

// Shared SEO settings. Child segments replace `openGraph` / `twitter` as whole objects,
// so every page builds them through `pageMetadata` to keep site name, locale and image.
export const SITE_NAME = 'FORMA.';
export const SITE_TAGLINE = 'FORMA. — Objects with character.';
export const SITE_DESCRIPTION = `Una colección de audio, living y accesorios para lo cotidiano.${process.env.NEXT_PUBLIC_STORE_MODE === 'live' ? ' Envíos a todo México y pago seguro con Mercado Pago.' : ' Tienda de demostración.'}`;
export const DEFAULT_OG_IMAGE = { url: '/products/hero.jpg', width: 2000, height: 1500, alt: 'FORMA. — objetos de diseño para lo cotidiano' };

/** Public origin used for canonical URLs, Open Graph, robots and sitemap. */
export function siteUrl(): URL {
  const raw =
    process.env.SITE_URL ||
    (process.env.VERCEL_PROJECT_PRODUCTION_URL && `https://${process.env.VERCEL_PROJECT_PRODUCTION_URL}`) ||
    (process.env.VERCEL_URL && `https://${process.env.VERCEL_URL}`) ||
    'http://localhost:3000';
  return new URL(raw);
}

/** Private or transactional pages: keep them out of search results. */
export const NO_INDEX: Metadata['robots'] = { index: false, follow: false };

type PageInput = {
  title: string;
  description: string;
  path: string;
  image?: { url: string; alt: string; width?: number; height?: number };
  absoluteTitle?: boolean;
};

export function pageMetadata({ title, description, path, image, absoluteTitle }: PageInput): Metadata {
  const images = [image ?? DEFAULT_OG_IMAGE];
  const shareTitle = absoluteTitle ? title : `${title} | ${SITE_NAME}`;
  return {
    title: absoluteTitle ? { absolute: title } : title,
    description,
    alternates: { canonical: path },
    openGraph: { type: 'website', siteName: SITE_NAME, locale: 'es_MX', url: path, title: shareTitle, description, images },
    twitter: { card: 'summary_large_image', title: shareTitle, description, images: images.map(i => i.url) },
  };
}

/** Trims text to a search-snippet friendly length without cutting words. */
export function snippet(text: string, max = 158): string {
  const clean = text.replace(/\s+/g, ' ').trim();
  if (clean.length <= max) return clean;
  const cut = clean.slice(0, max - 1);
  return `${cut.slice(0, cut.lastIndexOf(' ')).replace(/[\s,.;:—-]+$/, '')}…`;
}
