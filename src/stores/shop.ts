'use client';
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { api } from '@/lib/api/client';
import { ApiError, errorMessage } from '@/lib/api/http';
import { cartSchema, userSchema } from '@/lib/api/schemas';
import type { Cart, User } from '@/types';

// Client state backed by the REST API. The cart and the session live on the server (HttpOnly cookies);
// only the footer message preference is kept in localStorage.
type Registration = { firstName: string; lastName: string; email: string; password: string };
type ShopState = {
  cart: Cart | null; cartReady: boolean; cartBusy: boolean; cartError: string;
  user: User | null; sessionReady: boolean;
  announcement: string; drawer: boolean;
  init: () => Promise<void>;
  setDrawer: (open: boolean) => void;
  refreshCart: () => Promise<void>;
  add: (variantId: string, quantity?: number) => Promise<boolean>;
  quantity: (itemId: string, quantity: number) => Promise<void>;
  remove: (itemId: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  register: (data: Registration) => Promise<void>;
  logout: () => Promise<void>;
  setAnnouncement: (text: string) => void;
};

let started = false;
const cartFailure = (e: unknown) =>
  e instanceof ApiError && (e.status === 422 || e.status === 409)
    ? 'No hay suficientes unidades disponibles de este artículo.'
    : errorMessage(e, 'No pudimos actualizar tu bolsa. Inténtalo de nuevo.');

export const useShop = create<ShopState>()(persist((set, get) => {
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
    set({ user, sessionReady: true });
    try {
      set({ cart: user ? await api.post('/carts', cartSchema) : (await api.get('/carts/current', cartSchema)) ?? null, cartError: '' });
    } catch { set({ cart: null }); }
  }
  return {
    cart: null, cartReady: false, cartBusy: false, cartError: '', user: null, sessionReady: false,
    announcement: 'Objetos elegidos con intención.', drawer: false,
    async init() {
      if (started) return;
      started = true;
      try { localStorage.removeItem('forma-demo-v1'); } catch { /* storage unavailable */ }
      await Promise.all([
        api.get('/auth/session', userSchema).then(user => set({ user: user ?? null }), () => set({ user: null })).finally(() => set({ sessionReady: true })),
        get().refreshCart(),
      ]);
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
    async quantity(itemId, quantity) {
      await mutate(cart => api.patch(`/carts/${cart.id}/items/${itemId}`, cartSchema, { quantity }));
    },
    async remove(itemId) {
      await mutate(cart => api.delete(`/carts/${cart.id}/items/${itemId}`, cartSchema));
    },
    async login(email, password) {
      await syncAfterAuth(await api.post('/auth/login', userSchema, { email, password }));
    },
    async register(data) {
      await syncAfterAuth(await api.post('/auth/register', userSchema, data));
    },
    async logout() {
      await api.post('/auth/logout', null);
      await syncAfterAuth(null);
    },
    setAnnouncement: announcement => set({ announcement }),
  };
}, { name: 'forma-prefs-v1', storage: createJSONStorage(() => localStorage), partialize: ({ announcement }) => ({ announcement }) }));
