// The browser only ever talks to this Next.js origin: /api/v1/* is proxied to the Spring Boot backend,
// so session, guest and CSRF cookies are first-party and no CORS is involved.
const BACKEND_URL = (process.env.BACKEND_URL || 'http://localhost:8080').replace(/\/$/, '');
const backend = new URL(BACKEND_URL);
if (!['http:', 'https:'].includes(backend.protocol) || backend.username || backend.password
    || backend.search || backend.hash || backend.pathname !== '/') {
  throw new Error('BACKEND_URL must be an HTTP(S) origin without credentials');
}
const imageHosts = (process.env.IMAGE_ALLOWED_HOSTS || 'images.unsplash.com').split(',').map(s => s.trim()).filter(Boolean);
if (imageHosts.some(host => !/^[a-z0-9]+(?:[.-][a-z0-9]+)*$/i.test(host))) {
  throw new Error('IMAGE_ALLOWED_HOSTS must contain explicit DNS hostnames');
}

/** @type {import('next').NextConfig} */
const nextConfig = {
  poweredByHeader: false,
  images: {
    remotePatterns: imageHosts.map(hostname => ({ protocol: 'https', hostname, port: '', pathname: '/**' })),
    dangerouslyAllowSVG: false,
    dangerouslyAllowLocalIP: false,
    maximumRedirects: 0,
  },
  async headers() {
    return [{ source: '/:path*', headers: [
      { key: 'X-Content-Type-Options', value: 'nosniff' },
      { key: 'X-Frame-Options', value: 'DENY' },
      { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
      { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), payment=()' },
      ...(process.env.NODE_ENV === 'production' ? [{ key: 'Strict-Transport-Security', value: 'max-age=31536000' }] : []),
    ] }];
  },
  async rewrites() {
    return [{ source: '/api/v1/:path*', destination: `${BACKEND_URL}/api/v1/:path*` }];
  },
};
export default nextConfig;
