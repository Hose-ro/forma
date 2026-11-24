'use client';

import { Heart } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useShop } from '@/stores/shop';
import type { Product } from '@/types';

export function FavoriteButton({ product }: { product: Pick<Product, 'id' | 'name' | 'slug'> }) {
  const router = useRouter();
  const signedIn = useShop(state => Boolean(state.user));
  const sessionReady = useShop(state => state.sessionReady);
  const loading = useShop(state => state.favoritesLoading);
  const saved = useShop(state => state.favorites?.has(product.id) ?? false);
  const update = useShop(state => state.favoriteUpdates[product.id]);
  const toggle = useShop(state => state.toggleFavorite);
  const pending = update === 'pending';
  const title = !signedIn ? 'Inicia sesión para guardar favoritos'
    : saved ? 'Quitar de favoritos' : 'Guardar en favoritos';

  function click() {
    if (signedIn) void toggle(product.id);
    else router.push(`/account?next=${encodeURIComponent(`/product/${product.slug}`)}`);
  }

  return <div className="favorite-control">
    <button type="button" className="favorite-button" onClick={click}
      title={title} aria-label={signedIn ? `Favorito: ${product.name}` : title}
      aria-pressed={signedIn ? saved : undefined} aria-busy={pending || undefined}
      disabled={!sessionReady || loading || pending}>
      <Heart size={20} fill={saved ? 'currentColor' : 'none'} aria-hidden="true" />
    </button>
    {!pending && update && <p className="favorite-message error" role="alert">{update}</p>}
  </div>;
}
