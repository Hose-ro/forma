import { Home } from '@/components/home';
import { getSessionUser } from '@/lib/api/server';
import { catalogRepository } from '@/lib/catalog';
import { SITE_DESCRIPTION, SITE_TAGLINE, pageMetadata } from '@/lib/seo';

export const dynamic = 'force-dynamic';
export const metadata = pageMetadata({
  title: SITE_TAGLINE, absoluteTitle: true, description: SITE_DESCRIPTION, path: '/',
});

export default async function Page() {
  const customerSelection = getSessionUser().catch(() => null).then(async user => {
    const categoryIds = user?.categoryInterests ?? [];
    const preferred = categoryIds.length
      ? await catalogRepository.list({ categoryIds, sort: 'featured', size: 4 }).catch(() => null)
      : null;
    return { categoryIds, preferred };
  });

  const [products, categories, { categoryIds, preferred }] = await Promise.all([
    catalogRepository.list({ sort: 'featured', size: 8 }),
    catalogRepository.categories(),
    customerSelection,
  ]);

  const recommendations = preferred?.items ?? [];
  const featured = [...recommendations, ...products.items.filter(product =>
    product.featured && !recommendations.some(item => item.id === product.id))].slice(0, 4);

  const interests = new Set(categoryIds);
  const orderedCategories = [
    ...categories.filter(category => interests.has(category.id)),
    ...categories.filter(category => !interests.has(category.id)),
  ];
  return <Home featured={featured} categories={orderedCategories} personalized={recommendations.length > 0} />;
}
