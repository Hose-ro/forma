import { randomBytes } from 'node:crypto';
import { NextRequest, NextResponse } from 'next/server';

export function proxy(request: NextRequest) {
  const nonce = randomBytes(24).toString('base64');
  const dev = process.env.NODE_ENV !== 'production';
  const policy = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev ? " 'unsafe-eval'" : ''}`,
    // Motion and GSAP set inline styles. Script execution still requires the nonce.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' https: data: blob:",
    "font-src 'self'",
    `connect-src 'self'${dev ? ' ws: wss:' : ''}`,
    "object-src 'none'", "base-uri 'none'", "form-action 'self'", "frame-ancestors 'none'",
  ].join('; ');
  const headers = new Headers(request.headers);
  // Overwrite untrusted caller values before Next.js extracts script nonces.
  headers.set('x-nonce', nonce);
  headers.set('Content-Security-Policy', policy);
  // API rate limits must see each visitor, not this proxy. Caller-supplied copies are always dropped; the pair
  // is attached only when a shared secret is configured (BACKEND_PROXY_SECRET here = APP_PROXY_SECRET there).
  // CLIENT_IP_HEADER must be a header the hosting platform overwrites itself (x-real-ip on Vercel).
  headers.delete('x-olvenio-proxy-secret');
  headers.delete('x-olvenio-client-ip');
  const proxySecret = process.env.BACKEND_PROXY_SECRET;
  if (proxySecret && request.nextUrl.pathname.startsWith('/api/')) {
    headers.set('x-olvenio-proxy-secret', proxySecret);
    const ip = (request.headers.get(process.env.CLIENT_IP_HEADER || 'x-real-ip') || '').split(',')[0].trim();
    if (/^[0-9a-f:.]{3,45}$/i.test(ip)) headers.set('x-olvenio-client-ip', ip);
  }
  const response = NextResponse.next({ request: { headers } });
  response.headers.set('Content-Security-Policy', policy);
  if (/^\/(api|account|admin|cart|checkout)(\/|$)/.test(request.nextUrl.pathname)) {
    response.headers.set('Cache-Control', 'private, no-store');
  }
  return response;
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|products/).*)'],
};
