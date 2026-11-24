# Olvenio
Dominio de producción: `olvenio.com`; API prevista en `api.olvenio.com` (requiere configurar DNS y hosting).

Tienda de demostración: frontend Next.js (este directorio) + backend Spring Boot/PostgreSQL (`backend/`).

## Ejecutar en local
1. Backend (ver `backend/README.md`): `cd backend && set -a && source .env && set +a && mvn spring-boot:run` → http://localhost:8080
2. Frontend: `cp .env.example .env.local` (opcional), `npm install`, `npm run dev` → http://localhost:3000

Verificación: `npm run typecheck`, `npm run build`. Requiere Node.js 20.18.1+; la primera compilación necesita acceso a Google Fonts.

Para activar el escaneo local antes de cada commit y push, instala `gitleaks` y ejecuta `npm run hooks:install` en la raíz del repositorio. El instalador retira el ajuste local anterior `core.hooksPath=.githooks` después de copiar los hooks. Consulta [Detección de secretos](backend/SECURITY.md#detección-de-secretos).

## Cómo se conecta
- `next.config.mjs` proxya `/api/v1/*` a `BACKEND_URL` (variable solo de servidor). El navegador nunca llama a otro origen.
- `src/lib/api/`: `schemas.ts` (contratos zod), `http.ts` (errores Problem Details), `client.ts` (navegador, CSRF), `server.ts` (Server Components).
- `src/lib/catalog.ts`: catálogo desde la API en Server Components (`/`, `/shop`, `/product/[slug]`).
- `src/stores/shop.ts`: carrito y sesión contra la API. No se guardan tokens en localStorage: sesión e identidad de invitado viven en cookies HttpOnly.
- `/admin` exige sesión con rol ADMIN (comprobado en el servidor); cada operación la autoriza de nuevo el backend.

## Rutas
`/`, `/shop` (búsqueda, filtros, orden y paginación por URL), `/product/[slug]`, `/cart`, `/checkout`, `/checkout/confirmation/[id]`, `/account`, `/account/orders/[id]`, `/admin?view=overview|products|orders|suppliers|inventory|settings|payments`, `/policies/*`.

Mercado Pago Checkout Pro permanece en modo de pruebas. El backend valida el estado oficial antes de marcar un pedido como pagado; incluye firma de webhooks, conciliación y consulta administrativa de pagos. No hay envíos reales. El directorio de proveedores y el mensaje del footer son datos de demostración/locales.

## Entorno de pruebas estable

Configuración gratuita preparada para Vercel + Render + Neon: [guía de staging](deploy/STAGING.md).

## Producción

Pasos para abrir ventas reales (datos del negocio, envíos, Mercado Pago, dominio y correo): [deploy/PRODUCTION.md](deploy/PRODUCTION.md).

### Acceso opcional con Google

1. En [Google Auth Platform](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid?hl=es-419), configura la marca de Olvenio y crea un cliente OAuth de tipo **Aplicación web** para el entorno. Agrega el origen exacto de la tienda en **Orígenes autorizados de JavaScript** (por ejemplo, `http://localhost:3000` para desarrollo o `https://olvenio.com` en producción). Usa el aviso de privacidad del entorno en la pantalla de consentimiento.
2. Define `APP_GOOGLE_CLIENT_ID` con el ID del cliente en Spring Boot. Flyway aplica la migración `V21`. Define `GOOGLE_SIGN_IN_ENABLED=true` en Next.js y vuelve a desplegar o reinicia ambos servicios. La biblioteca usa un callback de JavaScript, así que basta con autorizar el origen.
3. En **Mi cuenta**, prueba un registro nuevo y la aceptación de términos. Para vincular una cuenta existente, entra con correo y contraseña, abre **Tu perfil**, selecciona **Vincular cuenta de Google** y vuelve a confirmar tu contraseña. Luego cierra sesión y comprueba que el botón de Google abre la misma cuenta.

El registro directo se habilita para direcciones de Gmail y Google Workspace. Si Google no aloja el correo, el cliente primero crea una cuenta de Olvenio, confirma su correo y la vincula desde el perfil. Para desactivar la integración, quita `APP_GOOGLE_CLIENT_ID` del backend y configura `GOOGLE_SIGN_IN_ENABLED=false` en el frontend.
