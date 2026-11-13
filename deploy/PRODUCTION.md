# Puesta en producción

La aplicación ya soporta todo lo necesario; lo que falta son **tus datos y tus cuentas**. Sigue los pasos en orden.
Staging (`render.yaml`, `deploy/STAGING.md`) se queda como entorno de pruebas y nunca comparte credenciales con producción.

## 1. Datos del negocio (Vercel → proyecto de producción → Environment Variables)

| Variable | Ejemplo |
| --- | --- |
| `NEXT_PUBLIC_STORE_MODE` | `live` (quita todas las etiquetas de demostración; ponlo al final, cuando abras ventas) |
| `NEXT_PUBLIC_BUSINESS_LEGAL_NAME` | `Mi Empresa S.A. de C.V.` o tu nombre completo |
| `NEXT_PUBLIC_BUSINESS_TAX_ID` | RFC |
| `NEXT_PUBLIC_BUSINESS_ADDRESS` | Domicilio completo |
| `NEXT_PUBLIC_BUSINESS_EMAIL` | `hola@tudominio.com` |
| `NEXT_PUBLIC_BUSINESS_PHONE` | Teléfono o WhatsApp |
| `NEXT_PUBLIC_BUSINESS_HOURS` | Horario de atención |
| `NEXT_PUBLIC_RETURN_DAYS` | `30` |
| `NEXT_PUBLIC_HANDLING_TIME` | `1 a 2 días hábiles` |
| `BACKEND_URL` | `https://api.tudominio.com` |
| `SITE_URL` | `https://tudominio.com` |
| `IMAGE_ALLOWED_HOSTS` | Host(s) donde alojes las fotografías |

Las variables `NEXT_PUBLIC_*` se incrustan al compilar: después de cambiarlas hay que **volver a desplegar**.
Con ellas se rellenan solos el pie de página, el Aviso de privacidad, los Términos y la página de Envíos, cambios y devoluciones.
Los textos legales son un modelo conforme a la LFPDPPP y la LFPC; haz que un abogado los revise antes de abrir.

## 2. Catálogo real

Entra a `/admin` → **Productos**: crea categorías y productos con nombre, descripción, precio, SKU, existencias y fotografías
(URL de imagen en un host incluido en `IMAGE_ALLOWED_HOSTS`). La base de producción arranca vacía: el catálogo de demostración
solo se siembra en dev/staging.

## 3. Envíos (variables del backend)

| Variable | Qué hace |
| --- | --- |
| `APP_SHIPPING_FLAT` | Tarifa para cualquier estado sin tarifa propia |
| `APP_SHIPPING_ZONES` | Tarifas por estado: `Ciudad de México=99;Jalisco=149` |
| `APP_SHIPPING_ONLY_ZONES` | `true` = solo se entrega en los estados de la lista |
| `APP_SHIPPING_FREE_FROM` | Envío gratis desde ese subtotal (vacío = nunca) |
| `APP_SHIPPING_DELIVERY_ESTIMATE` | Texto del plazo, p. ej. `3 a 7 días hábiles` |

El servidor calcula el cargo; el checkout y la política de envíos muestran exactamente esas reglas.

**Gestión de cada pedido** (`/admin` → Pedidos): pago aprobado → *Preparar* → captura paquetería y guía → *Marcar enviado*
(el cliente recibe un correo con la guía) → *Marcar entregado*. Abre el pedido para ver la dirección de entrega.

## 4. Infraestructura de producción

1. **Base de datos**: crea un proyecto Neon nuevo (no una rama del de staging). Ejecuta `deploy/neon/01_bootstrap.sql`,
   arranca la API una vez (Flyway aplica V1–V9) y luego `deploy/neon/02_runtime_grants.sql`. Usa contraseñas nuevas.
2. **Respaldos**: activa la restauración a un punto en el tiempo de Neon (plan de pago, ≥ 7 días) y programa un `pg_dump` semanal a otro almacenamiento.
3. **API**: en Render crea un Blueprint nuevo con ruta `deploy/render.production.yaml` y completa las variables marcadas.
   Usa un plan siempre encendido: una instancia dormida retrasa los webhooks de pago.
4. **Frontend**: proyecto de Vercel propio para producción con las variables del paso 1.
5. **Administrador**: define `APP_ADMIN_EMAIL` y `APP_ADMIN_PASSWORD` (≥ 10 caracteres, única) para el primer arranque;
   después **borra `APP_ADMIN_PASSWORD`** del host. La cuenta ya queda creada.
6. **Monitoreo**: monitor de disponibilidad (UptimeRobot, Better Stack…) sobre `https://api.tudominio.com/api/v1/categories`
   y `https://tudominio.com`, con alertas a tu correo; activa las notificaciones de despliegue fallido en Render y Vercel.

## 5. Mercado Pago real

1. En <https://www.mercadopago.com.mx/developers/panel> abre tu aplicación → **Credenciales de producción** (requiere activar la cuenta).
2. En Render (producción): `MERCADOPAGO_ENVIRONMENT=production`, `MERCADOPAGO_ACCESS_TOKEN` (token de producción),
   `MERCADOPAGO_COLLECTOR_ID` (tu User ID numérico).
3. **Webhooks** → modo productivo → URL `https://api.tudominio.com/api/v1/webhooks/mercadopago`, evento **Pagos**.
   Copia la clave secreta en `MERCADOPAGO_WEBHOOK_SECRET`.
4. Verificación antes de abrir: con un producto de precio bajo haz una compra real con tu propia tarjeta y comprueba que
   el pedido pasa a **Pagado**, llega el correo de confirmación, el pago aparece en `/admin` → Pagos, y que un reembolso
   hecho desde Mercado Pago cambia el pedido a **Reembolsado**.

El backend rechaza la mezcla de entornos: en `production` solo acepta pagos reales del vendedor configurado; en `test` solo cuentas de prueba.

## 6. Dominio y correo

1. **Dominio**: en Vercel agrega `tudominio.com` y `www`; en Render agrega `api.tudominio.com`. Crea los registros DNS que indican;
   ambos emiten HTTPS automáticamente. La API debe ser subdominio del mismo dominio que la tienda.
2. Actualiza `APP_FRONTEND_ORIGIN`, `APP_PUBLIC_FRONTEND_URL` (`https://tudominio.com`), `APP_PUBLIC_BACKEND_URL`
   (`https://api.tudominio.com`), `BACKEND_URL` y `SITE_URL`.
3. **Correo**: contrata un proveedor SMTP transaccional (Resend, Brevo, Postmark, Amazon SES…), verifica tu dominio con los
   registros SPF/DKIM que te den y configura `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT=587`, `SPRING_MAIL_USERNAME`,
   `SPRING_MAIL_PASSWORD`, `APP_MAIL_FROM` (`Tienda <pedidos@tudominio.com>`), `APP_ORDERS_NOTIFY_EMAIL` (tu buzón: recibe copia
   de cada pedido pagado y las respuestas de clientes) y `APP_STORE_NAME`. Sin `SPRING_MAIL_HOST` no se envía ningún correo.
4. Comprobación final: candado HTTPS en tienda y API, `https://tudominio.com/sitemap.xml` y `/robots.txt` con tu dominio,
   enlaces del pie de página, registro e inicio de sesión, y los correos de confirmación y de envío en una compra de prueba.
