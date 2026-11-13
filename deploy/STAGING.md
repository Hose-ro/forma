# FORMA: entorno gratuito de pruebas

Frontend Next.js en Vercel Hobby, Spring Boot en Render Free y PostgreSQL en Neon Free.
URLs del proveedor, sin comprar dominio y sin túneles locales. Pagos exclusivamente de prueba.
No se exportan usuarios, pedidos, cookies ni credenciales administrativas de la base local.

## 1. Neon

Crea un proyecto gratuito dedicado a FORMA staging, preferentemente en AWS us-west-2
(cerca del backend Render en Oregon). Usa una conexión **directa**, sin `-pooler`, con
`sslmode=verify-full`. Configura `DATABASE_URL` en formato JDBC, sin usuario ni contraseña:
`jdbc:postgresql://HOST/neondb?sslmode=verify-full`.

Usa el propietario del proyecto como rol de migración y crea **mediante SQL** un login independiente
`forma_runtime`, sin privilegios de administración ni propiedad de tablas. No lo crees con el flujo
administrativo de la consola/API: Neon asigna `neon_superuser` a esos roles. Usa una contraseña
aleatoria privada y concede únicamente los permisos de estos scripts. Guarda ambos
pares de credenciales solo en Render o en un archivo local ignorado por Git.

Conectado como el migrador, ejecuta `deploy/neon/01_bootstrap.sql` antes del primer arranque.
Tras el primer arranque y las migraciones, ejecuta `deploy/neon/02_runtime_grants.sql` **antes
de usar el checkout**. Esto habilita escrituras de negocio y mantiene los historiales append-only.
Verifica que forma_runtime no tenga rolsuper/rolcreaterole/rolcreatedb/rolbypassrls; el backend
rechaza esas configuraciones y rechaza compartir el rol de runtime con Flyway.

## 2. Vercel: reservar el proyecto

Importa `Hose-ro/forma`, directorio raíz del repositorio, framework Next.js y Node.js 24.
Usa un proyecto dedicado, por ejemplo `forma-staging`, con su dirección `.vercel.app` asignada.
Configura únicamente variables de servidor:

- `BACKEND_URL`: origen HTTPS real de Render, sin barra final ni ruta.
- `IMAGE_ALLOWED_HOSTS=images.unsplash.com`.

El build normal es `npm run build`. Como alternativa ante un fallo específico de Turbopack,
usa `npm run build -- --webpack`, que ya se verificó localmente. Redeploy después de cambiar
BACKEND_URL: las reglas del proxy se calculan durante la compilación.
No copies el `.env.local` local a Vercel: contiene el backend de localhost.

## 3. Render

Crea un Blueprint desde `render.yaml`; solo define un servicio Docker de plan **free**.
El contenedor compila y ejecuta Java 21 como usuario no root. Define las variables marcadas
`sync: false` usando los secretos de Neon y las credenciales **Testing** de Mercado Pago:

- `DATABASE_URL`, `DATABASE_PASSWORD`, `DATABASE_MIGRATION_USERNAME`, `DATABASE_MIGRATION_PASSWORD`.
- `APP_FRONTEND_ORIGIN` y `APP_PUBLIC_FRONTEND_URL`: el mismo origen HTTPS fijo de Vercel.
- `APP_PUBLIC_BACKEND_URL`: origen HTTPS de Render.
- `MERCADOPAGO_ACCESS_TOKEN`, `MERCADOPAGO_COLLECTOR_ID`, `MERCADOPAGO_WEBHOOK_SECRET`.

El perfil `staging` aplica migraciones, añade catálogo de demostración sin sobrescribir
productos existentes, exige cookies Secure y separa credenciales de runtime/migración.
Puede crear el administrador `admin@forma.test` con la contraseña aleatoria de Render.
El secreto de sesión también se genera en Render; no cambies esos valores en cada deploy.
La contraseña generada se consulta en el panel de Render y se conserva fuera de Git.

Primero pueden reservarse ambos proyectos y luego completar las URLs cruzadas. No compartas
las URLs como operativas hasta que ambos despliegues estén listos y los permisos SQL aplicados.

## 4. Mercado Pago y validación

En la aplicación de pruebas, cambia la URL de Payments Webhooks a:
`https://HOST-DE-RENDER/api/v1/webhooks/mercadopago`.
Si Mercado Pago genera otra clave de firma, actualiza solo Render y redeploy.

Comprueba catálogo, acceso administrativo, un pedido de prueba aprobado y el historial
HTTP 200 de Mercado Pago. Repite una notificación para verificar que no duplique eventos.
Las preferencias locales ya creadas conservan sus URLs antiguas; para staging crea un pedido
nuevo con comprador/medios de prueba. Conserva el pedido local anterior como evidencia local.
No marques pagos manualmente ni habilites credenciales reales.

## Límites aceptados del plan gratuito

Render suspende el backend tras 15 minutos sin tráfico entrante; el primer acceso puede
requerir esperar y reintentar. Al suspenderse o redeployar se pierden sesiones de login en
memoria; los pedidos permanecen en Neon. Una notificación durante el arranque puede fallar
y Mercado Pago deberá reintentarla. La conciliación recupera pagos cuando la aplicación está
activa; en staging revisa cada 10 minutos para reducir actividad de la base. No se agregan
pings artificiales para mantener servicios despiertos.

Neon también suspende su cómputo inactivo y aplica cuotas gratuitas. No equivale a disponibilidad
continua ni a un entorno apto para cobros reales. Vercel Hobby se usa aquí como demo personal
no comercial. No se activa ningún plan de pago ni dominio personalizado.

Referencias: [Render Free](https://render.com/docs/free),
[Render Docker](https://render.com/docs/docker), [Neon Free](https://neon.com/docs/introduction/plans),
[Vercel Hobby](https://vercel.com/docs/plans/hobby).
