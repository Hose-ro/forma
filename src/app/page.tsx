import { Home, type HomeHero } from '@/components/home';
import { getSessionUser, serverGet } from '@/lib/api/server';
import { heroContentSchemas, heroViewsSchema } from '@/lib/api/schemas';
import { catalogRepository } from '@/lib/catalog';
import {
  DEFAULT_HEROES, GIFT_BANDS, HERO_VARIANTS, heroForTime, momentForTime, parseHeroVariant,
  type HeroContents, type HeroVariant,
} from '@/lib/home-heroes';
import { SITE_DESCRIPTION, SITE_TAGLINE, pageMetadata } from '@/lib/seo';
import type { Product } from '@/types';

export const dynamic = 'force-dynamic';
export const metadata = pageMetadata({
  title: SITE_TAGLINE, absoluteTitle: true, description: SITE_DESCRIPTION, path: '/',
});

/** How many worlds get a tab in "Lo mejor de cada mundo"; the rest stay reachable from the world bar. */
const MAX_WORLD_TABS = 6;

type Published = { enabled: HeroVariant[]; contents: HeroContents };

/** Heroes edited in the admin. Falls back to the built-in defaults when the API is down or has nothing valid. */
async function publishedHeroes(): Promise<Published> {
  const rows = await serverGet('/home/heroes', heroViewsSchema).catch(() => null);
  const contents: Partial<HeroContents> = {};
  for (const row of rows ?? []) {
    const parsed = heroContentSchemas[row.variant].safeParse(row.content);
    if (row.enabled && parsed.success) Object.assign(contents, { [row.variant]: parsed.data });
  }
  const enabled = HERO_VARIANTS.filter(variant => contents[variant]);
  return enabled.length
    ? { enabled, contents: { ...DEFAULT_HEROES, ...contents } }
    : { enabled: [...HERO_VARIANTS], contents: DEFAULT_HEROES };
}

async function findProducts(slugs: string[]): Promise<Map<string, Product>> {
  const found = await Promise.all(slugs.map(slug => catalogRepository.find(slug).catch(() => null)));
  return new Map(found.filter((p): p is Product => p !== null).map(p => [p.slug, p]));
}

async function loadHero(variant: HeroVariant, contents: HeroContents, now: Date): Promise<HomeHero> {
  if (variant === 'c') {
    const { campaigns } = contents.c;
    const products = await findProducts(campaigns.map(c => c.productSlug));
    const ready = campaigns.flatMap(c => {
      const product = products.get(c.productSlug);
      return product ? [{ ...c, product }] : [];
    });
    if (ready.length) return { variant: 'c', campaigns: ready };
  }
  if (variant === 'd') {
    const { title, italic, moments } = contents.d;
    const products = await findProducts([...new Set(moments.flatMap(m => m.productSlugs))]);
    const ready = moments.map(m => ({
      ...m, products: m.productSlugs.flatMap(slug => products.get(slug) ?? []),
    })).filter(m => m.products.length);
    const wanted = momentForTime(now);
    if (ready.length) {
      return { variant: 'd', title, italic, moments: ready, initial: ready.some(m => m.id === wanted) ? wanted : ready[0].id };
    }
  }
  // B builds itself from the live categories; A only needs its own content. Both stand in when C or D lack products.
  return variant === 'b' ? { variant: 'b', content: contents.b } : { variant: 'a', content: contents.a };
}

export default async function Page({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const now = new Date();
  const preview = parseHeroVariant((await searchParams).hero);

  const customerSelection = getSessionUser().catch(() => null).then(async user => {
    const categoryIds = user?.categoryInterests ?? [];
    const preferred = categoryIds.length
      ? await catalogRepository.list({ categoryIds, sort: 'featured', size: 4 }).catch(() => null)
      : null;
    return { categoryIds, preferred };
  });

  const heroSelection = publishedHeroes().then(({ enabled, contents }) => {
    const variant = preview && enabled.includes(preview) ? preview : heroForTime(now, undefined, enabled);
    return loadHero(variant, contents, now);
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
    heroSelection,
  ]);

  const recommendations = preferred?.items ?? [];
  const featured = [...recommendations, ...products.items.filter(product =>
    product.featured && !recommendations.some(item => item.id === product.id))].slice(0, 4);

  return <Home hero={hero} featured={featured} categories={categories} worlds={worlds}
    gifts={gifts.filter(shelf => shelf.products.length)} personalized={recommendations.length > 0} />;
}
