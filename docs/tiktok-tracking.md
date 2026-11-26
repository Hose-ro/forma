# Medición de TikTok en Olvenio

El seguimiento está desactivado por defecto. Next.js usa el aviso de cookies de la tienda y el proxy existente hacia Spring Boot. El backend guarda el consentimiento y los eventos en PostgreSQL. Una tarea de Spring entrega la cola a TikTok con un máximo de cuatro intentos y un límite de cuatro segundos por llamada. Mercado Pago, Stripe, PayPal y Conekta conservan sus flujos de verificación y validación de webhooks.

| Evento | Canal | Origen |
| --- | --- | --- |
| `PageView` | Pixel | Navegación a una página pública, una vez por cambio de ruta |
| `ViewContent` | Pixel | Ficha de un producto y su variante visible |
| `AddToWishlist` | Ambos | Primera adición de un producto a los favoritos de la cuenta |
| `Search` | Pixel | Búsqueda aplicada en el catálogo. El texto libre permanece en Olvenio |
| `AddToCart` | Ambos | Adición confirmada al carrito, con el precio y la cantidad añadida |
| `InitiateCheckout` | Ambos | Cotización validada y persistida |
| `PlaceAnOrder` | Ambos | Primera confirmación de la cotización como pedido persistido |
| `CompleteRegistration` | Ambos | Alta de cuenta y sesión iniciada |
| `Purchase` | Ambos | Pedido pagado, con el importe y la moneda del pago verificado |

`AddPaymentInfo` queda pendiente porque las pasarelas capturan los datos de pago en su propio sitio y Olvenio carece de una confirmación de ese paso. TikTok prevé retirar `PlaceAnOrder` en 2027. [Eventos estándar](https://ads.tiktok.com/resources/help/article/standard-events-parameters) · [Calendario de cambios](https://ads.tiktok.com/resources/help/article/how-to-adopt-tiktoks-updated-standard-events?lang=es-419)

El identificador de catálogo es el UUID de variante. Los importes de checkout y pedidos incluyen el envío conforme al resumen del backend. En los eventos compartidos, el servidor devuelve un identificador opaco en `X-Olvenio-Marketing-Event`. Las dos versiones de `Purchase` comparten el mismo ID determinista por pedido. El navegador puede recibir ese ID durante las primeras 24 horas de confirmación, mediante una respuesta autorizada del pedido y con el consentimiento de la sesión de origen. PostgreSQL evita duplicados incluso cuando se repiten notificaciones de las pasarelas o hay varias instancias. [Deduplicación de TikTok](https://ads.tiktok.com/help/article/event-deduplication)

Events API usa `ttclid` o `ttp` como identificadores de coincidencia. Cuando ambos faltan, el evento se mide solo por Pixel. El SDK vive en un documento aislado de otro origen y recibe rutas públicas sin parámetros ni fragmentos. Revocar detiene el Pixel en el navegador, invalida el consentimiento persistido, borra los identificadores de publicidad almacenados por Olvenio y cancela las entregas pendientes. Una solicitud ya transmitida está fuera del alcance de la revocación. Si falta conexión, el sitio reintenta al recuperarla y muestra el estado pendiente hasta que el backend confirme la revocación. La preferencia vence a los 180 días. Los eventos en PostgreSQL se conservan durante 30 días. [Contrato de Events API](https://business-api.tiktok.com/portal/docs/events-api-for-web/v1.3) · [Consentimiento del Pixel](https://business-api.tiktok.com/portal/docs/pixel-cookie-consent-mode/v1.3)

## Configuración y despliegue

1. Despliega primero el backend con `TIKTOK_TRACKING_ENABLED=false`. Flyway aplica la migración aditiva `V24__marketing_tracking.sql`. Si el rol de ejecución configurado en `DATABASE_USERNAME` ya existe, la migración le da acceso a las dos tablas nuevas. Si se crea después, ejecuta [el script de permisos](../deploy/neon/02_runtime_grants.sql) como rol de migraciones.
2. Despliega Next.js. Usa `BACKEND_URL=https://api.olvenio.com` en Vercel y `APP_FRONTEND_ORIGIN=https://olvenio.com` en Render para producción. El navegador debe poder abrir la URL HTTPS del backend. El documento del Pixel solo permite ser enmarcado por el origen exacto de la tienda. En staging, usa los orígenes correspondientes. El origen del backend se usa también en la CSP de Next.js.
3. En TikTok Ads Manager, abre **Tools → Events Manager → Data sources** y selecciona **Olvenio - Web - Production**. En **Settings**, mantén desactivados **Automatic Advanced Matching** y **Enhanced Data Postback**. Revisa que no haya reglas de Event Builder u otra instalación del Pixel que envíen los mismos eventos. Las cookies publicitarias de primera parte se habilitan en el navegador tras el consentimiento. En **Events API**, genera el Access Token de esa fuente de datos. [Configuración de TikTok](https://ads.tiktok.com/help/article/get-started-pixel)
4. En el servicio de producción de Render, abre **Environment**. En **Environment Variables**, pulsa **Edit** si aparece y usa **Add variable**. Guarda el token en `TIKTOK_EVENTS_API_ACCESS_TOKEN` y agrega las demás variables de la tabla. Cuando termines de verificar staging, cambia el interruptor a `true` y elige **Save and deploy** si el código ya está desplegado. El token se guarda en Render y la tienda recibe únicamente la configuración pública por la API. [Variables de Render](https://render.com/docs/configure-environment-variables)

| Variable del backend | Valor de producción |
| --- | --- |
| `TIKTOK_TRACKING_ENABLED` | `false` hasta la activación. `true` para activar |
| `TIKTOK_PIXEL_ID` | `DC395ARC77UFT0UGGH30` |
| `TIKTOK_EVENTS_API_ACCESS_TOKEN` | Token de esa fuente de datos, guardado por el propietario |
| `TIKTOK_EVENTS_API_URL` | `https://business-api.tiktok.com/open_api/v1.3/event/track/` |
| `TIKTOK_TEST_EVENT_CODE` | Vacío en producción |

Para desactivar ambos canales, cambia `TIKTOK_TRACKING_ENABLED` a `false` y selecciona **Save and deploy** en Render. Dejan de registrarse y enviarse eventos del servidor. El navegador comprueba la configuración al recargar, volver a la página o durante su revisión periódica. Las entregas que ya se iniciaron pueden completarse. La cola se limita a los eventos con menos de 24 horas de antigüedad.

## Verificación en Events Manager

1. Usa una fuente de datos dedicada a pruebas, su token y el código de **Test Events → Test Server Events** como `TIKTOK_TEST_EVENT_CODE` en staging. La configuración bloquea el píxel de producción fuera de su dominio y el uso de un código de prueba con ese píxel. Los envíos del servidor fuera del dominio de producción requieren un código.
2. Abre la tienda de staging con **Test Browser Events** y TikTok Pixel Helper. En una sesión nueva, verifica que no aparezcan solicitudes de TikTok antes de elegir o después de rechazar. Acepta, abre una ficha y realiza una búsqueda. Comprueba una sola carga del SDK, un `PageView` por cambio de ruta, `ViewContent` y `Search` sin el término. Revoca desde **Aviso de cookies → Configurar cookies**, vuelve a navegar y verifica que cesen las solicitudes.
3. Con una elección válida y un `ttp` disponible, añade un producto, cotiza y registra el pedido. Comprueba los eventos Browser y Server con el mismo nombre e ID. En staging, algunos navegadores bloquean las cookies en el documento de otro sitio. Para verificar ambos canales, usa un navegador de pruebas que permita esas cookies en staging o una visita con un identificador de clic autorizado.
4. Usa el entorno de pruebas de Mercado Pago. Comprueba que un pago pendiente o rechazado no genera `Purchase`. Con un pago de prueba confirmado, comprueba el mismo ID, importe y moneda en ambos canales. Repite la notificación de prueba y vuelve a abrir el pedido para comprobar que la compra se cuenta una vez. Comprueba que las páginas privadas y los términos de búsqueda quedan fuera del payload.
5. Revisa **Diagnostics** y **Deduplication** en la fuente de datos. La validación operativa se completa cuando TikTok confirma la recepción de ambos canales y la deduplicación. [Verificación de eventos de TikTok](https://business-api.tiktok.com/portal/docs/verify-events-setup/v1.3)

Los logs usan `marketing outcome=...`, con nombre e identificador opaco de evento y, cuando procede, estado HTTP, código de TikTok e intento. La CSP del documento del SDK se limita a los orígenes de TikTok autorizados y solo permite enmarcar el documento desde el origen configurado de la tienda. [CSP del Pixel](https://business-api.tiktok.com/portal/docs/work-with-csp/v1.3)

## Pruebas locales

- `node --experimental-strip-types --test tests/marketing-pixel.test.mjs` comprueba consentimiento, sanitización de rutas, carga única y revocación con un SDK simulado.
- `mvn -f backend/pom.xml -Dtest=TikTokEventsClientTest test` verifica configuración, payload y códigos de respuesta con fixtures.
- `OLVENIO_MARKETING_DB_TEST=true mvn -f backend/pom.xml -Dtest=MarketingPersistenceTest test` comprueba persistencia, coincidencia de ID, exclusión de pagos inválidos y reintentos contra una base PostgreSQL desechable indicada por `DATABASE_URL` y `DATABASE_USERNAME`. El cliente de TikTok es un mock.
