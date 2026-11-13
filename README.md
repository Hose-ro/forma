# FORMA.
Tienda de demostración: frontend Next.js (este directorio) + backend Spring Boot/PostgreSQL (`backend/`).

## Ejecutar en local
1. Backend (ver `backend/README.md`): `cd backend && set -a && source .env && set +a && mvn spring-boot:run` → http://localhost:8080
2. Frontend: `cp .env.example .env.local` (opcional), `npm install`, `npm run dev` → http://localhost:3000

Verificación: `npm run typecheck`, `npm run build`. Requiere Node.js 20.9+; la primera compilación necesita acceso a Google Fonts.

## Cómo se conecta
- `next.config.mjs` proxya `/api/v1/*` a `BACKEND_URL` (variable solo de servidor). El navegador nunca llama a otro origen.
- `src/lib/api/`: `schemas.ts` (contratos zod), `http.ts` (errores Problem Details), `client.ts` (navegador, CSRF), `server.ts` (Server Components).
- `src/lib/catalog.ts`: catálogo desde la API en Server Components (`/`, `/shop`, `/product/[slug]`).
- `src/stores/shop.ts`: carrito y sesión contra la API. No se guardan tokens en localStorage: sesión e identidad de invitado viven en cookies HttpOnly.
- `/admin` exige sesión con rol ADMIN (comprobado en el servidor); cada operación la autoriza de nuevo el backend.

## Rutas
`/`, `/shop` (búsqueda, filtros, orden y paginación por URL), `/product/[slug]`, `/cart`, `/checkout`, `/checkout/confirmation/[id]`, `/account`, `/account/orders/[id]`, `/admin?view=overview|products|orders|suppliers|inventory|settings|payments`, `/policies/*`.

Mercado Pago Checkout Pro permanece en modo de pruebas. El backend valida el estado oficial antes de marcar un pedido como pagado; incluye firma de webhooks, conciliación y consulta administrativa de pagos. No hay envíos reales. El directorio de proveedores y el mensaje del footer son datos de demostración/locales.
