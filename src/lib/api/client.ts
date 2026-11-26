'use client';
import type { z } from 'zod';
import { ApiError, request } from './http';
import { csrfSchema } from './schemas';

// Browser-side API access. Same-origin (/api/v1 is proxied by Next.js), so the HttpOnly session and
// guest cookies travel automatically; nothing sensitive is kept in localStorage.
const BASE = '/api/v1';
const SAFE = new Set(['GET', 'HEAD']);

function csrfCookie(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

async function csrfToken(refresh = false): Promise<string> {
  if (!refresh) {
    const existing = csrfCookie();
    if (existing) return existing;
  }
  return (await request(`${BASE}/auth/csrf`, csrfSchema, { credentials: 'same-origin', cache: 'no-store' })).token;
}

async function call<S extends z.ZodType>(method: string, path: string, schema: S | null, body?: unknown, headers: Record<string, string> = {}, onResponse?: (headers: Headers) => void): Promise<z.infer<S>> {
  const send = async (refresh: boolean) =>
    request(`${BASE}${path}`, schema, {
      method, body, credentials: 'same-origin', cache: 'no-store', onResponse,
      headers: SAFE.has(method) ? headers : { ...headers, 'X-XSRF-TOKEN': await csrfToken(refresh) },
    });
  try {
    return await send(false);
  } catch (e) {
    // The CSRF token rotates on login/logout: retry once with a fresh one.
    if (!SAFE.has(method) && e instanceof ApiError && e.status === 403) return send(true);
    throw e;
  }
}

async function withMarketingReceipt<S extends z.ZodType>(
  method: 'GET' | 'POST' | 'PUT', path: string, schema: S, body?: unknown, headers?: Record<string, string>,
) {
  let eventId: string | undefined;
  const data = await call(method, path, schema, body, headers, responseHeaders => {
    const id = responseHeaders.get('X-Olvenio-Marketing-Event');
    if (id && /^[a-f0-9]{64}$/.test(id)) eventId = id;
  });
  return { data, eventId };
}

export const api = {
  get: <S extends z.ZodType>(path: string, schema: S) => call('GET', path, schema),
  post: <S extends z.ZodType>(path: string, schema: S | null, body?: unknown, headers?: Record<string, string>) => call('POST', path, schema, body, headers),
  getWithMarketingReceipt: <S extends z.ZodType>(path: string, schema: S) =>
    withMarketingReceipt('GET', path, schema),
  postWithMarketingReceipt: <S extends z.ZodType>(path: string, schema: S, body?: unknown, headers?: Record<string, string>) =>
    withMarketingReceipt('POST', path, schema, body, headers),
  putWithMarketingReceipt: <S extends z.ZodType>(path: string, schema: S) =>
    withMarketingReceipt('PUT', path, schema),
  patch: <S extends z.ZodType>(path: string, schema: S | null, body?: unknown) => call('PATCH', path, schema, body),
  put: <S extends z.ZodType>(path: string, schema: S | null, body?: unknown) => call('PUT', path, schema, body),
  delete: <S extends z.ZodType>(path: string, schema: S | null) => call('DELETE', path, schema),
};
