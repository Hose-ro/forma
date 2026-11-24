'use client';

import Link from 'next/link';
import { Heart } from 'lucide-react';
import { useShop } from '@/stores/shop';
import { ProductCard } from './product-card';

export function Favorites() {
  const signedIn = useShop(state => Boolean(state.user));
  const sessionReady = useShop(state => state.sessionReady);
  const favorites = useShop(state => state.favorites);
  const error = useShop(state => state.favoritesError);
  const load = useShop(state => state.loadFavorites);

  return <div className="page">
    <div className="page-intro">
      <nav className="breadcrumbs" aria-label="Ruta">
        <Link href="/account">Mi cuenta</Link><span>/</span><span>Favoritos</span>
      </nav>
      <h1>Favoritos</h1>
    </div>
    {!sessionReady ? <p role="status">Cargando tu cuenta…</p>
      : !signedIn ? <div className="empty">
        <p>Inicia sesión para ver y guardar tus productos favoritos.</p>
        <Link href="/account?next=%2Faccount%2Ffavorites" className="button">Iniciar sesión</Link>
      </div>
      : error ? <div className="empty">
        <p className="error" role="alert">{error}</p>
        <button type="button" className="button" onClick={() => void load()}>Reintentar</button>
      </div>
      : !favorites ? <p role="status">Cargando tus favoritos…</p>
      : favorites.size ? <div className="product-grid catalog-grid">
        {Array.from(favorites.values(), product => <ProductCard product={product} key={product.id} />)}
      </div>
      : <div className="empty">
        <Heart size={32} aria-hidden="true" />
        <p>Aún no tienes favoritos. Toca el corazón de un producto para guardarlo aquí.</p>
        <Link href="/shop" className="button">Explorar la colección</Link>
      </div>}
  </div>;
}
