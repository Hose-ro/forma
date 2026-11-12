// The browser only ever talks to this Next.js origin: /api/v1/* is proxied to the Spring Boot backend,
// so session, guest and CSRF cookies are first-party and no CORS is involved.
const BACKEND_URL = (process.env.BACKEND_URL || 'http://localhost:8080').replace(/\/$/, '');

/** @type {import('next').NextConfig} */
const nextConfig = {
  async rewrites() {
    return [{ source: '/api/v1/:path*', destination: `${BACKEND_URL}/api/v1/:path*` }];
  },
};
export default nextConfig;
