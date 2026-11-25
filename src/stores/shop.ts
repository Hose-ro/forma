'use client';
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { api } from '@/lib/api/client';
import { ApiError, errorMessage } from '@/lib/api/http';
import { cartSchema, favoritesSchema, productSummarySchema, userSchema } from '@/lib/api/schemas';
import { fromSummary } from '@/lib/product-summary';
import type { Cart, Product, User } from '@/types';

// Client state backed by the REST API. The cart and the session live on the server (HttpOnly cookies);
// only the footer message preference is kept in localStorage.
type ShopState = {
  cart: Cart | null; cartReady: boolean; cartBusy: boolean; cartError: string;
  user: User | null; sessionReady: boolean;
  favorites: Map<string, Product> | null;
  favoritesLoading: boolean; favoritesError: string;
  favoriteUpdates: Partial<Record<string, string>>;
  loadFavorites: () => Promise<void>;
  toggleFavorite: (productId: string) => Promise<void>;
  announcement: string; drawer: boolean;
  init: () => Promise<void>;
  setDrawer: (open: boolean) => void;
  refreshCart: () => Promise<void>;
  add: (variantId: string, quantity?: number) => Promise<boolean>;
  buy: (variantId: string, quantity: number) => Promise<boolean>;
  quantity: (itemId: string, quantity: number) => Promise<void>;
  remove: (itemId: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  syncAfterAuth: (user: User) => Promise<void>;
  logout: () => Promise<void>;
  setAnnouncement: (text: string) => void;
};

let started = false;
const cartFailure = (e: unknown) =>
  e instanceof ApiError && (e.status === 422 || e.status === 409)
    ? 'No hay suficientes unidades disponibles de este artículo.'
    : errorMessage(e, 'No pudimos actualizar tu bolsa. Inténtalo de nuevo.');

export const useShop = create<ShopState>()(persist((set, get) => {
  let sessionVersion = 0;

  function setSession(user: User | null) {
    sessionVersion++;
    set({ user, sessionReady: true, favorites: null, favoritesLoading: false, favoritesError: '', favoriteUpdates: {} });
    if (user) void get().loadFavorites();
  }

  /** Runs a cart mutation; the server response is the single source of truth for quantities and totals. */
  async function mutate(run: (cart: Cart) => Promise<Cart>): Promise<boolean> {
    if (get().cartBusy) return false;
    set({ cartBusy: true, cartError: '' });
    try {
      const cart = get().cart ?? await api.post('/carts', cartSchema);
      set({ cart: await run(cart), cartBusy: false });
      return true;
    } catch (e) {
      set({ cartBusy: false, cartError: cartFailure(e) });
      if (e instanceof ApiError && e.status === 404) await get().refreshCart();
      return false;
    }
  }
  /** After the identity changes, the server decides which cart applies (and merges the guest cart on login). */
  async function syncAfterAuth(user: User | null) {
    setSession(user);
    try {
      set({ cart: user ? await api.post('/carts', cartSchema) : (await api.get('/carts/current', cartSchema)) ?? null, cartError: '' });
    } catch { set({ cart: null }); }
  }
  return {
    cart: null, cartReady: false, cartBusy: false, cartError: '', user: null, sessionReady: false,
    favorites: null, favoritesLoading: false, favoritesError: '', favoriteUpdates: {},
    announcement: 'Objetos elegidos con intención.', drawer: false,
    async init() {
      if (started) return;
      started = true;
      // Remove browser data from the original demo; this is a legacy key, not the current brand.
      try { localStorage.removeItem('forma-demo-v1'); } catch { /* storage unavailable */ }
      await Promise.all([
        api.get('/auth/session', userSchema).then(user => setSession(user ?? null), () => setSession(null)),
        get().refreshCart(),
      ]);
    },
    async loadFavorites() {
      const { user, favorites, favoritesLoading } = get();
      if (!user || favorites || favoritesLoading) return;
      const version = sessionVersion;
      set({ favoritesLoading: true, favoritesError: '' });
      try {
        const products = await api.get('/me/favorites', favoritesSchema);
        if (version === sessionVersion) {
          set({ favorites: new Map(products.map(product => [product.id, fromSummary(product)])), favoritesLoading: false });
        }
      } catch (error) {
        if (version === sessionVersion) {
          set({ favoritesLoading: false, favoritesError: errorMessage(error, 'No pudimos cargar tus favoritos.') });
        }
      }
    },
    async toggleFavorite(productId) {
      const version = sessionVersion;
      if (!get().favorites) await get().loadFavorites();
      const { user, favorites, favoriteUpdates, favoritesError } = get();
      if (version !== sessionVersion || !user || favoriteUpdates[productId] === 'pending') return;
      if (!favorites) {
        if (favoritesError) set({ favoriteUpdates: { ...favoriteUpdates, [productId]: favoritesError } });
        return;
      }

      set({ favoriteUpdates: { ...favoriteUpdates, [productId]: 'pending' } });
      const path = `/me/favorites/${productId}`;
      try {
        const product = favorites.has(productId)
          ? await api.delete(path, null).then(() => null)
          : fromSummary(await api.put(path, productSummarySchema));
        if (version !== sessionVersion) return;
        set(state => {
          const updated = new Map(state.favorites);
          updated.delete(productId);
          const updates = { ...state.favoriteUpdates };
          delete updates[productId];
          return { favorites: product ? new Map([[productId, product], ...updated]) : updated, favoriteUpdates: updates };
        });
      } catch (error) {
        if (version !== sessionVersion) return;
        const message = error instanceof ApiError && error.status === 404
          ? 'Este producto ya no está publicado.'
          : errorMessage(error, 'No pudimos actualizar tus favoritos. Inténtalo de nuevo.');
        set(state => ({ favoriteUpdates: { ...state.favoriteUpdates, [productId]: message } }));
      }
    },
    setDrawer: drawer => set({ drawer }),
    async refreshCart() {
      try {
        set({ cart: (await api.get('/carts/current', cartSchema)) ?? null, cartReady: true });
      } catch (e) {
        set({ cartReady: true, cartError: errorMessage(e, 'No pudimos cargar tu bolsa.') });
      }
    },
    async add(variantId, quantity = 1) {
      const ok = await mutate(cart => api.post(`/carts/${cart.id}/items`, cartSchema, { variantId, quantity }));
      set({ drawer: true });
      return ok;
    },
    /** Leaves exactly this quantity of the variant in the bag, so buying twice never doubles the units. */
    buy: (variantId, quantity) => mutate(cart => {
      const line = cart.items.find(item => item.variantId === variantId);
      return line
        ? api.patch(`/carts/${cart.id}/items/${line.id}`, cartSchema, { quantity })
        : api.post(`/carts/${cart.id}/items`, cartSchema, { variantId, quantity });
    }),
    async quantity(itemId, quantity) {
      await mutate(cart => api.patch(`/carts/${cart.id}/items/${itemId}`, cartSchema, { quantity }));
    },
    async remove(itemId) {
      await mutate(cart => api.delete(`/carts/${cart.id}/items/${itemId}`, cartSchema));
    },
    async login(email, password) {
      await syncAfterAuth(await api.post('/auth/login', userSchema, { email, password }));
    },
    syncAfterAuth,
    async logout() {
      await api.post('/auth/logout', null);
      await syncAfterAuth(null);
    },
    setAnnouncement: announcement => set({ announcement }),
  };
}, { name: 'olvenio-prefs-v1', storage: createJSONStorage(() => localStorage), partialize: ({ announcement }) => ({ announcement }) }));
