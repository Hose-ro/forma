import { cookies } from 'next/headers';
import 'server-only';
import type { z } from 'zod';
import { ApiError, request } from './http';
import { userSchema } from './schemas';

// Server-side API access (Server Components only). BACKEND_URL is never sent to the browser.
const BACKEND_URL = (process.env.BACKEND_URL || 'http://localhost:8080').replace(/\/$/, '');

export function serverGet<S extends z.ZodType>(path: string, schema: S, headers?: Record<string, string>) {
  return request(`${BACKEND_URL}/api/v1${path}`, schema, { cache: 'no-store', headers });
}

/** The logged-in user according to the backend session cookie of the current request, or null. */
export async function getSessionUser() {
  const cookie = (await cookies()).toString();
  if (!cookie) return null;
  try {
    return await serverGet('/auth/me', userSchema, { cookie });
  } catch (e) {
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) return null;
    throw e;
  }
}
