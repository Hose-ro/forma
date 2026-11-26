'use client';

import { useSyncExternalStore } from 'react';
import { z } from 'zod';
import { api } from '@/lib/api/client';
import type { Order } from '@/types';
import { createPixel, publicPage, type TikTokEvent } from './pixel';

const CONSENT_PATH = '/marketing/consent';
const SIGNAL = 'olvenio-marketing-choice-v1';
const DENIED_COOKIE = 'OLVENIO_MARKETING_DENIED';
const preferenceSchema = z.object({
  enabled: z.boolean(),
  pixelId: z.string().nullish(),
  expiresAt: z.string().nullish(),
});

type Preference = z.infer<typeof preferenceSchema>;
type Status = { enabled: boolean; granted: boolean; pendingRevocation: boolean; decided: boolean };
const initial: Status = { enabled: false, granted: false, pendingRevocation: false, decided: false };
const listeners = new Set<() => void>();
const sentIds = new Set<string>();
let status = initial;
let context: Preference | undefined;
let pixel: ReturnType<typeof createPixel> | undefined;
let path: string | undefined;
let reportedPage: string | null = null;
let viewedProduct: string | undefined;
let searched: string | undefined;
let lastClick: string | undefined;
let revision = 0;
let saving = false;
let listening = false;
let expiryTimer: number | undefined;

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function useMarketingConsent() {
  return useSyncExternalStore(subscribe, () => status, () => initial);
}

function storefront() {
  return !!path && !/^\/admin(?:\/|$)/.test(path);
}

function trackable() {
  if (!path) return false;
  return !!publicPage(path) || /^\/(account\/orders|checkout\/confirmation)\/[a-f0-9-]{36}$/.test(path)
    || /^\/checkout\/(success|pending|failure)$/.test(path);
}

function update(next: Status) {
  const changed = Object.keys(next).some(key => next[key as keyof Status] !== status[key as keyof Status]);
  if (!changed) return;
  status = next;
  listeners.forEach(listener => listener());
}

function deniedCookie() {
  try { return /(?:^|;\s*)OLVENIO_MARKETING_DENIED=1(?:;|$)/.test(document.cookie); }
  catch { return false; }
}

function pendingRevocation() {
  try { return localStorage.getItem(SIGNAL) === 'deny-pending'; }
  catch { return status.pendingRevocation; }
}

function signal(value: string) {
  try {
    localStorage.setItem(SIGNAL, value);
  } catch { /* The cookie still enforces the current choice. */ }
}

function clickId() {
  const candidate = new URLSearchParams(window.location.search).get('ttclid');
  return candidate && /^[A-Za-z0-9._~-]{8,512}$/.test(candidate) ? candidate : undefined;
}

function attribute(values: { ttp?: string; ttclid?: string }) {
  if (!status.granted || !trackable()) return;
  void api.post('/marketing/attribution', null, values).catch(() => {});
}

function clearBrowserCookies() {
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = `${DENIED_COOKIE}=1; Path=/; Max-Age=15552000; SameSite=Lax${secure}`;
  for (const entry of document.cookie.split(';')) {
    const name = entry.trim().split('=', 1)[0];
    if (!/^(_ttp|_tt_enable_cookie|ttcsid(?:_[A-Za-z0-9]+)?)$/.test(name)) continue;
    const expired = `${name}=; Path=/; Max-Age=0; SameSite=Lax${secure}`;
    document.cookie = expired;
    document.cookie = `${expired}; Domain=${window.location.hostname}`;
  }
}

function stop() {
  pixel?.disable();
  reportedPage = null;
  viewedProduct = undefined;
  searched = undefined;
  lastClick = undefined;
}

function reconcile() {
  const expiry = Date.parse(context?.expiresAt ?? '');
  const pending = pendingRevocation();
  const denied = deniedCookie();
  const pixelId = !pending && !denied && expiry > Date.now() ? context?.pixelId : null;
  const granted = !!pixelId;
  update({
    enabled: !!context?.enabled || pending, granted, pendingRevocation: pending,
    decided: granted || pending || denied,
  });
  window.clearTimeout(expiryTimer);
  if (granted) {
    expiryTimer = window.setTimeout(reconcile, Math.min(expiry - Date.now(), 2_147_000_000));
  }
  if (!trackable() || !pixelId) {
    stop();
    return;
  }

  pixel?.enable(pixelId);
  const page = publicPage(path!);
  if (page !== reportedPage) {
    reportedPage = page;
    if (page) pixel?.send({ type: 'page', path: page });
  }
  const ttclid = clickId();
  if (ttclid && ttclid !== lastClick) {
    lastClick = ttclid;
    attribute({ ttclid });
  }
}

async function refreshConsent() {
  const startedAt = revision;
  try {
    const next = await api.get(CONSENT_PATH, preferenceSchema);
    if (startedAt !== revision || saving) return;
    context = next;
    reconcile();
  } catch {
    if (startedAt !== revision || saving) return;
    if (context?.pixelId) context = { enabled: context.enabled };
    reconcile();
  }
}

export async function setMarketingConsent(allow: boolean) {
  if (saving) return;
  saving = true;
  revision++;
  if (!allow) {
    signal('deny-pending');
    stop();
    update({ ...status, granted: false, pendingRevocation: true });
    try { clearBrowserCookies(); } catch { /* Browser storage can be unavailable. */ }
  }
  try {
    context = await api.post(CONSENT_PATH, preferenceSchema, { allowed: allow, ttclid: allow ? clickId() : undefined });
    signal(allow ? 'accepted' : 'denied');
    update({ ...status, pendingRevocation: false });
    reconcile();
  } finally {
    saving = false;
  }
}

export function marketingNavigation(frameOrigin: string, pathname: string) {
  if (path !== pathname) {
    path = pathname;
    viewedProduct = undefined;
    searched = undefined;
  }
  if (!listening) {
    listening = true;
    pixel = createPixel(frameOrigin, ttp => attribute({ ttp }));
    const sync = () => {
      if (!storefront()) return;
      if (pendingRevocation()) void setMarketingConsent(false).catch(() => {});
      else void refreshConsent();
    };
    window.addEventListener('online', sync);
    window.setInterval(() => { if (context?.enabled || pendingRevocation()) sync(); }, 60_000);
    window.addEventListener('pageshow', sync);
    window.addEventListener('storage', event => {
      if (event.key !== SIGNAL) return;
      if (event.newValue === 'deny-pending' || event.newValue === 'denied') context = undefined;
      reconcile();
      if (event.newValue === 'accepted') sync();
    });
    if (storefront()) sync();
  } else if (storefront() && !context && !saving && !pendingRevocation()) {
    void refreshConsent();
  }
  reconcile();
}

export function commerceProperties(currency: string, value: number, lines: {
  variantId?: string | null; unitPrice: number; quantity: number;
}[]) {
  const contents = lines.flatMap(line => line.variantId
    ? [{ content_id: line.variantId, price: line.unitPrice, quantity: line.quantity }] : []);
  return { currency, value, ...(contents.length && { content_type: 'product', contents }) };
}

const orderProperties = (order: Order) => commerceProperties(order.currency, order.total, order.items);

function track(event: TikTokEvent, properties: Record<string, unknown>, id?: string, persistent = false) {
  if (!status.granted || !trackable() || deniedCookie() || !(Date.parse(context?.expiresAt ?? '') > Date.now())) return;
  try {
    const eventId = id ?? crypto.randomUUID();
    if (sentIds.has(eventId)) return;
    if (persistent) {
      // The server only exposes purchase receipts for 24 hours. Keep the browser marker across reloads.
      const purchaseReceipts = 'olvenio-purchase-receipts-v1';
      try {
        const existing: [string, number][] = JSON.parse(localStorage.getItem(purchaseReceipts) ?? '[]');
        const recent = existing.filter(([, time]) => time > Date.now() - 86_400_000);
        if (recent.some(([id]) => id === eventId)) return;
        localStorage.setItem(purchaseReceipts, JSON.stringify([...recent.slice(-99), [eventId, Date.now()]]));
      } catch { /* In-memory deduplication still applies. */ }
    }
    sentIds.add(eventId);
    pixel?.send({ type: 'event', event, id: eventId, properties });
  } catch { /* The underlying storefront action already succeeded. */ }
}

export const marketing = {
  product(slug: string, currency: string, variantId: string, price: number) {
    if (!status.granted || viewedProduct === slug || window.location.pathname !== `/product/${slug}`) return;
    viewedProduct = slug;
    track('ViewContent', commerceProperties(currency, price, [{ variantId, unitPrice: price, quantity: 1 }]));
  },
  search(query: string) {
    if (!status.granted || window.location.pathname !== '/shop' || searched === query) return;
    searched = query;
    if (query.trim()) track('Search', {});
  },
  cart(id: string | undefined, currency: string, variantId: string, unitPrice: number, quantity: number) {
    if (id) track('AddToCart', commerceProperties(currency, Number((unitPrice * quantity).toFixed(2)), [{ variantId, unitPrice, quantity }]), id);
  },
  wishlist(id: string | undefined, variantId?: string | null) {
    if (id) track('AddToWishlist', variantId
      ? { content_type: 'product', contents: [{ content_id: variantId }] } : {}, id);
  },
  checkout(id: string | undefined, order: Order) {
    if (id) track('InitiateCheckout', orderProperties(order), id);
  },
  order(id: string | undefined, order: Order) {
    if (id) track('PlaceAnOrder', orderProperties(order), id);
  },
  register(id: string | undefined) {
    if (id) track('CompleteRegistration', {}, id);
  },
  purchase(id: string | undefined, order: Order) {
    if (id && order.status === 'PAID' && order.paymentStatus === 'APPROVED') {
      track('Purchase', orderProperties(order), id, true);
    }
  },
};
