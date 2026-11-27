# Olvenio: puesta en producción

La aplicación cubre el flujo de venta, pero abrir ventas exige además **tus datos, tus cuentas y validar en staging** lo que
solo se puede comprobar con servicios reales (ver «Antes de activar live» al final). Sigue los pasos en orden.
Dominio de la tienda: `olvenio.com`. Subdominio previsto para la API: `api.olvenio.com`.
Staging (`render.yaml`, `deploy/STAGING.md`) se queda como entorno de pruebas y nunca comparte credenciales con producción.
Estas direcciones requieren configurar DNS y asociar los dominios en los proveedores antes de usarlas.

## 1. Datos del negocio (Vercel → proyecto de producción → Environment Variables)

| Variable | Ejemplo |
| --- | --- |
| `NEXT_PUBLIC_STORE_MODE` | `live` (quita todas las etiquetas de demostración; ponlo al final, cuando abras ventas) |
| `NEXT_PUBLIC_BUSINESS_LEGAL_NAME` | `Mi Empresa S.A. de C.V.` o tu nombre completo |
| `NEXT_PUBLIC_BUSINESS_TAX_ID` | RFC |
| `NEXT_PUBLIC_BUSINESS_ADDRESS` | Domicilio completo |
| `NEXT_PUBLIC_BUSINESS_EMAIL` | `hola@olvenio.com` (crea y verifica este buzón antes de publicarlo) |
| `NEXT_PUBLIC_BUSINESS_PHONE` | Teléfono o WhatsApp |
| `NEXT_PUBLIC_BUSINESS_HOURS` | Horario de atención |
| `NEXT_PUBLIC_RETURN_DAYS` | `30` |
| `NEXT_PUBLIC_HANDLING_TIME` | `1 a 2 días hábiles` |
| `BACKEND_URL` | `https://api.olvenio.com` |
| `SITE_URL` | `https://olvenio.com` |
| `IMAGE_ALLOWED_HOSTS` | Host(s) donde alojes las fotografías |

Las variables `NEXT_PUBLIC_*` se incrustan al compilar: después de cambiarlas hay que **volver a desplegar**.
Con ellas se rellenan solos el pie de página, el Aviso de privacidad, los Términos y la página de Envíos, cambios y devoluciones.
Los textos legales son un modelo conforme a la LFPDPPP y la LFPC; haz que un abogado los revise antes de abrir.

## 2. Catálogo real

Entra a `/admin` → **Categorías** y crea al menos una; después, en **Productos**, crea cada producto con nombre, descripción,
materiales, fotografías (dirección `https://` en un host incluido en `IMAGE_ALLOWED_HOSTS`; el editor rechaza las demás) y una o
más variantes con SKU, precio y existencias. Los productos nuevos nacen como borrador hasta marcar «Activo». La base de producción arranca vacía: el catálogo de demostración
solo se siembra en dev/staging.

## 3. Envíos (variables del backend)

| Variable | Qué hace |
| --- | --- |
| `APP_SHIPPING_ZONES` | Estados con cobertura: `Ciudad de México;Jalisco` |
| `APP_SHIPPING_ONLY_ZONES` | `true` = solo se entrega en los estados de la lista |
| `APP_SHIPPING_DELIVERY_ESTIMATE` | Texto del plazo, p. ej. `3 a 7 días hábiles` |

El envío es gratis en todos los pedidos: el servidor siempre lo cobra en $0 y no hay variables de tarifa.
El checkout y la política de envíos muestran la cobertura y el plazo configurados aquí.

**Gestión de cada pedido** (`/admin` → Pedidos): pago aprobado → *Preparar* → captura paquetería y guía → *Marcar enviado*
(el cliente recibe un correo con la guía) → *Marcar entregado*. Abre el pedido para ver la dirección de entrega.

## 4. Infraestructura de producción

1. **Base de datos**: crea un proyecto Neon nuevo (no una rama del de staging). Crea el rol `olvenio_runtime` con una contraseña privada para `DATABASE_USERNAME`. Ejecuta `deploy/neon/01_bootstrap.sql`,
   arranca la API una vez (Flyway aplica V1–V24) y luego ejecuta `deploy/neon/02_runtime_grants.sql` como propietario de las migraciones. Usa contraseñas nuevas.
2. **Respaldos**: activa la restauración a un punto en el tiempo de Neon (plan de pago, ≥ 7 días) y programa un `pg_dump` semanal a otro almacenamiento.
3. **API**: en Render crea un Blueprint nuevo con ruta `deploy/render.production.yaml` y completa las variables marcadas.
   Usa un plan siempre encendido: una instancia dormida retrasa los webhooks de pago.
4. **Frontend**: proyecto de Vercel propio para producción con las variables del paso 1.
5. **Administrador**: define `APP_ADMIN_EMAIL` y `APP_ADMIN_PASSWORD` (≥ 10 caracteres, única) para el primer arranque;
   después **borra `APP_ADMIN_PASSWORD`** del host. La cuenta ya queda creada.
6. **Monitoreo**: monitor de disponibilidad (UptimeRobot, Better Stack…) sobre `https://api.olvenio.com/api/v1/categories`
   y `https://olvenio.com`, con alertas a tu correo; activa las notificaciones de despliegue fallido en Render y Vercel.

Para actualizar una base existente, toma un respaldo verificable antes de desplegar y ensaya la actualización en una copia.
En una ventana de mantenimiento, despliega la API para que Flyway aplique V23 con el rol de migraciones.
Para instalaciones con `olvenio_runtime`, ejecuta el `02_runtime_grants.sql` actualizado con ese mismo propietario.
Si el entorno usa otro rol de ejecución, V23 aplica la revocación al configurado en `DATABASE_USERNAME`.
Antes de reabrir el tráfico, confirma que el rol de ejecución conserva `SELECT`, `INSERT` y `UPDATE` sobre `users` y `orders`,
y tiene permisos efectivos de solo lectura e inserción en las tablas de historial. Si los scripts detectan permisos
heredados que permiten borrar o alterar datos protegidos, revisa las membresías del rol y los permisos de `PUBLIC`
antes de reintentar.

## 5. Mercado Pago real

1. En <https://www.mercadopago.com.mx/developers/panel> abre tu aplicación → **Credenciales de producción** (requiere activar la cuenta).
2. En Render (producción): `MERCADOPAGO_ENVIRONMENT=production`, `MERCADOPAGO_ACCESS_TOKEN` (token de producción),
   `MERCADOPAGO_COLLECTOR_ID` (tu User ID numérico).
3. **Webhooks** → modo productivo → URL `https://api.olvenio.com/api/v1/webhooks/mercadopago`, evento **Pagos**.
   Copia la clave secreta en `MERCADOPAGO_WEBHOOK_SECRET`.
4. Verificación antes de abrir: con un producto de precio bajo haz una compra real con tu propia tarjeta y comprueba que
   el pedido pasa a **Pagado**, llega el correo de confirmación, el pago aparece en `/admin` → Pagos, y que un reembolso
   hecho desde Mercado Pago cambia el pedido a **Reembolsado**.

El backend rechaza la mezcla de entornos: en `production` solo acepta pagos reales del vendedor configurado; en `test` solo cuentas de prueba.

## 6. Dominio y correo

1. **Dominio**: en Vercel agrega `olvenio.com` y `www.olvenio.com` (redirige `www` al dominio principal); en Render agrega `api.olvenio.com`. Crea los registros DNS que indican;
   ambos emiten HTTPS automáticamente. La API debe ser subdominio del mismo dominio que la tienda.
2. Actualiza `APP_FRONTEND_ORIGIN`, `APP_PUBLIC_FRONTEND_URL` (`https://olvenio.com`), `APP_PUBLIC_BACKEND_URL`
   (`https://api.olvenio.com`), `BACKEND_URL` y `SITE_URL`.
3. **Correo**: contrata un proveedor SMTP transaccional (Resend, Brevo, Postmark, Amazon SES…), verifica tu dominio con los
   registros SPF/DKIM que te den y configura `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT=587`, `SPRING_MAIL_USERNAME`,
   `SPRING_MAIL_PASSWORD`, `APP_MAIL_FROM` (`Olvenio <pedidos@olvenio.com>`), `APP_ORDERS_NOTIFY_EMAIL` (tu buzón: recibe copia
   de cada pedido pagado y las respuestas de clientes) y `APP_STORE_NAME=Olvenio`. Sin `SPRING_MAIL_HOST` no se envía ningún correo.
   Producción y staging exigen STARTTLS en el puerto SMTP configurado y verifican el nombre del servidor en el certificado.
   Si el servidor no ofrece STARTTLS o falla la verificación del certificado, el intento se interrumpe antes de autenticar.
   La conexión, lectura y escritura tienen un límite de 10 s cada una. Revisa la cola y los correos fallidos si hay una interrupción.
4. Comprobación final: candado HTTPS en tienda y API, `https://olvenio.com/sitemap.xml` y `/robots.txt` con tu dominio,
   enlaces del pie de página, registro e inicio de sesión, y los correos de confirmación y de envío en una compra de prueba.

## Antes de activar live

Estos puntos están implementados pero **no se han ejercitado contra servicios reales**; valídalos en staging:

- **Límites por visitante**: define el mismo secreto (32+ caracteres) en `BACKEND_PROXY_SECRET` (Vercel) y `APP_PROXY_SECRET`
  (API). Sin él, todos los compradores comparten la dirección del proxy y pueden bloquearse entre sí. Comprueba con dos redes
  distintas que cada una tiene su propio límite, y que `CLIENT_IP_HEADER` (por defecto `x-real-ip`) es una cabecera que tu
  hosting sobrescribe.
- **Reservas**: un pedido sin pagar conserva su inventario `APP_RESERVATION_TTL` (24 h por defecto); la preferencia de
  Mercado Pago vence a la misma hora. Prueba: abandono (el pedido se cancela solo y el stock regresa ~15 min después del
  vencimiento), cancelación desde admin con preferencia abierta, pago en efectivo/transferencia pendiente (la reserva se
  conserva) y creación de preferencia con error de red seguida de reintento del comprador.
- **Correo**: confirma que la confirmación y la guía llegan, y que reiniciar la API a mitad de un envío no lo pierde
  (tabla `order_emails`; `status='FAILED'` requiere revisión manual). También el enlace de recuperación de contraseña y el
  de confirmación de correo (necesitan `APP_PUBLIC_FRONTEND_URL` y `APP_MAIL_FROM`).
- **Catálogo desde cero**: en una base vacía crea categoría, producto con fotografías propias y variantes, y completa una compra.


## Seguimiento y posventa

- **Códigos postales.** El backend consulta [Postali](https://postali.app/api) con el código de cinco dígitos. El cliente elige una colonia y el servidor valida municipio y estado antes de crear la cotización. Comprueba el acceso saliente a `postali.app` desde Render.
- **Seguimiento.** Configura `APP_PUBLIC_FRONTEND_URL` y SMTP. Los correos de invitado incluyen un enlace de seguimiento válido durante 90 días. El enlace abre el estado de un solo pedido y su guía. Los clientes registrados consultan el pedido en su cuenta.
- **Alertas.** En **Administración → Resumen**, consulta pagos en revisión, correos fallidos, pedidos sin preparar, solicitudes de cambio o factura e intentos de guía pendientes de conciliación. Los correos fallidos de pedidos se pueden poner de nuevo en cola desde el aviso. Ajusta `APP_PREPARATION_ALERT_HOURS` si el umbral de 24 horas cambia.
- **Cambios.** Ajusta `APP_RETURN_DAYS` en Render y `NEXT_PUBLIC_RETURN_DAYS` en Vercel al plazo publicado. El cliente solicita el cambio o devolución en su pedido. Administración gestiona la respuesta desde la ficha y ejecuta cualquier reembolso con el procesador de pago.
- **Facturas.** El titular de la cuenta puede solicitar el CFDI desde un pedido pagado. Emítelo en el facturador habitual y adjunta PDF y XML timbrado desde **Administración → Pedidos → Pedido → Solicitud de factura**. El sistema coteja los datos del XML y el total. El titular puede descargar ambos archivos desde su cuenta. El correo del negocio sigue disponible para clientes invitados.
- **Guías de Envia.** Configura en Render `APP_ENVIA_TOKEN`, `APP_ENVIA_ORIGIN_JSON` y `APP_ENVIA_CARRIERS` (códigos separados por comas, máximo cuatro). Para el origen, incluye `name`, `street`, `district` cuando aplique, `city`, `state` con el código de Envia, `postalCode`, `country` con valor `MX` y `phone`. Ve a **Administración → Pedidos → Pedido → Envío**, captura el paquete, cotiza y selecciona un servicio. Genera e imprime la guía antes de marcar el pedido como enviado. Si hubo un timeout, concilia el intento en Envia antes de comprar otra guía. Los envíos manuales se mantienen disponibles desde la lista de pedidos. Usa primero `APP_ENVIA_ENVIRONMENT=sandbox` en staging y pasa a `production` con el token de producción en el perfil `prod`. [Documentación de Envia](https://docs.envia.com/docs/domestic-shipping-workflow)

## Medición publicitaria de TikTok

Consulta la [guía de TikTok](../docs/tiktok-tracking.md) para aplicar V24 y verificar Pixel y Events API antes de activar la medición.
