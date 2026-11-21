import { Home, type HomeHero } from '@/components/home';
import { getSessionUser } from '@/lib/api/server';
import { catalogRepository } from '@/lib/catalog';
import {
  GIFT_BANDS, HERO_C_CAMPAIGNS, HERO_D_MOMENTS, heroForTime, momentForTime, parseHeroVariant, type HeroVariant,
} from '@/lib/home-heroes';
import { SITE_DESCRIPTION, SITE_TAGLINE, pageMetadata } from '@/lib/seo';
import type { Product } from '@/types';

export const dynamic = 'force-dynamic';
export const metadata = pageMetadata({
  title: SITE_TAGLINE, absoluteTitle: true, description: SITE_DESCRIPTION, path: '/',
});

/** How many worlds get a tab in "Lo mejor de cada mundo"; the rest stay reachable from the world bar. */
const MAX_WORLD_TABS = 6;

async function findProducts(slugs: string[]): Promise<Map<string, Product>> {
  const found = await Promise.all(slugs.map(slug => catalogRepository.find(slug).catch(() => null)));
  return new Map(found.filter((p): p is Product => p !== null).map(p => [p.slug, p]));
}

async function loadHero(variant: HeroVariant, now: Date): Promise<HomeHero> {
  if (variant === 'c') {
    const products = await findProducts(HERO_C_CAMPAIGNS.map(c => c.productSlug));
    const campaigns = HERO_C_CAMPAIGNS.flatMap(c => {
      const product = products.get(c.productSlug);
      return product ? [{ ...c, product }] : [];
    });
    if (campaigns.length) return { variant: 'c', campaigns };
  }
  if (variant === 'd') {
    const products = await findProducts([...new Set(HERO_D_MOMENTS.flatMap(m => m.productSlugs))]);
    const moments = HERO_D_MOMENTS.map(m => ({
      ...m, products: m.productSlugs.flatMap(slug => products.get(slug) ?? []),
    })).filter(m => m.products.length);
    const wanted = momentForTime(now);
    if (moments.length) return { variant: 'd', moments, initial: moments.some(m => m.id === wanted) ? wanted : moments[0].id };
  }
  // B builds itself from the live categories; A is static. Both are safe fallbacks when C or D lack products.
  return { variant: variant === 'b' ? 'b' : 'a' };
}

export default async function Page({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const now = new Date();
  const variant = parseHeroVariant((await searchParams).hero) ?? heroForTime(now);

  const customerSelection = getSessionUser().catch(() => null).then(async user => {
    const categoryIds = user?.categoryInterests ?? [];
    const preferred = categoryIds.length
      ? await catalogRepository.list({ categoryIds, sort: 'featured', size: 4 }).catch(() => null)
      : null;
    return { categoryIds, preferred };
  });

  const giftShelves = Promise.all(GIFT_BANDS.map(async band => {
    const page = await catalogRepository.list({ minPrice: band.min, maxPrice: band.max, available: true, sort: 'featured', size: 4 })
      .catch(() => null);
    const params = new URLSearchParams();
    if (band.min) params.set('min', band.min);
    if (band.max) params.set('max', band.max);
    return { id: band.id, label: band.label, href: `/shop?${params}`, products: page?.items ?? [] };
  }));

  // Categories the customer cares about come first, both in the world bar and in the world tabs.
  const orderedCategories = Promise.all([catalogRepository.categories(), customerSelection])
    .then(([categories, { categoryIds }]) => {
      const interests = new Set(categoryIds);
      return [
        ...categories.filter(category => interests.has(category.id)),
        ...categories.filter(category => !interests.has(category.id)),
      ];
    });

  const worldShelves = orderedCategories.then(categories => Promise.all(
    categories.slice(0, MAX_WORLD_TABS).map(async category => {
      const page = await catalogRepository.list({ category: category.slug, sort: 'featured', size: 4 }).catch(() => null);
      return page?.items.length ? { category, products: page.items, total: page.totalElements } : null;
    }),
  )).then(worlds => worlds.filter(world => world !== null));

  const [products, categories, { preferred }, worlds, gifts, hero] = await Promise.all([
    catalogRepository.list({ sort: 'featured', size: 8 }),
    orderedCategories,
    customerSelection,
    worldShelves,
    giftShelves,
    loadHero(variant, now),
  ]);

  const recommendations = preferred?.items ?? [];
  const featured = [...recommendations, ...products.items.filter(product =>
    product.featured && !recommendations.some(item => item.id === product.id))].slice(0, 4);

  return <Home hero={hero} featured={featured} categories={categories} worlds={worlds}
    gifts={gifts.filter(shelf => shelf.products.length)} personalized={recommendations.length > 0} />;
}
