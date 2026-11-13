# FORMA backend

Java 21 · Spring Boot 3.5 · PostgreSQL · Flyway. Monolito modular (`identity`, `catalog`, `cart`, `checkout`, `orders`, `admin`, `shared`).

## Ejecutar en local

```sh
createdb velora                      # PostgreSQL 13+, con rol local configurado
cp .env.example .env                 # ajusta valores
# Configura APP_SESSION_SECRET con un valor aleatorio (openssl rand -base64 48).
set -a; source .env; set +a
mvn spring-boot:run                  # perfil dev: migraciones + datos de demo + admin opcional
```

- API: http://localhost:8080/api/v1 · OpenAPI: http://localhost:8080/swagger-ui.html
- Pruebas: `mvn test`
- Producción y límites conocidos: [SECURITY.md](SECURITY.md).

## Integración con Next.js

- El frontend proxya `/api/v1/*` hacia este backend (`BACKEND_URL` en la raíz), así que el navegador solo habla con su propio origen.
- Sesión en cookie `VELORA_SESSION` (HttpOnly, SameSite=Lax, Secure con `APP_COOKIE_SECURE=true`). `GET /auth/session` devuelve 204 sin sesión.
- CSRF: `GET /api/v1/auth/csrf` → enviar `X-XSRF-TOKEN` en POST/PATCH/DELETE. El token rota tras login/logout.
- Invitados: cookie HttpOnly `VELORA_GUEST` (se emite al crear carrito o cotización). Identifica el carrito y los pedidos de invitado; en la base solo se guarda su HMAC. Clientes no navegador pueden enviar el mismo valor en `X-Cart-Token` / `X-Order-Token`.
- Checkout: `POST /checkout/quotes` → `POST /orders` con `Idempotency-Key`.
- Pagos de prueba: Checkout Pro; solo el estado consultado y validado con la API de Mercado Pago puede marcar un pedido como `PAID`.

## Mercado Pago (solo pruebas)

1. Configura una aplicación Checkout Pro para México y usa sus credenciales de **Testing**. El prefijo puede ser `APP_USR`; no determina por sí solo el ambiente. Usa comprador/vendedor de prueba y tarjetas oficiales de prueba.
2. Define las variables `MERCADOPAGO_ACCESS_TOKEN`, `MERCADOPAGO_WEBHOOK_SECRET`, `MERCADOPAGO_ENVIRONMENT=test`, `MERCADOPAGO_COLLECTOR_ID` (cuenta receptora de prueba) y ambas `APP_PUBLIC_*_URL` con HTTPS público. Los secretos se cargan exclusivamente en Spring Boot. Mantén las cookies en el mismo origen del frontend mediante el proxy Next existente.
3. En Webhooks selecciona Payments y registra `APP_PUBLIC_BACKEND_URL/api/v1/webhooks/mercadopago`; copia la clave de firma a la variable backend. Valida también con el simulador oficial. Algunas modalidades de prueba no envían notificaciones automáticas; la conciliación busca por `external_reference`.
4. Reinicia el backend (Flyway aplica V7). Crea un pedido, revisa su total y pulsa **Pagar con Mercado Pago**. Las tres rutas de retorno consultan el estado autorizado, sin confiar en parámetros de aprobación.

La integración usa `init_point` en `www.mercadopago.com.mx/checkout/v1/redirect`, también en pruebas. Antes de iniciar o reanudar el checkout verifica que la credencial pertenezca al vendedor configurado, marcado `test_user` y del sitio mexicano `MLM`. Conserva ambiente `test` y la verificación de importe/moneda/referencia/collector coincidentes. Un recurso `live_mode=true` solo se admite tras verificar por API que el vendedor es `test_user` y el comprador corresponde a una identidad de prueba mexicana (etiqueta `test_user`, o usuario `TESTUSER` y correo de proveedor `@testuser.com`). El flag por sí solo nunca autoriza un pago. Producción sigue bloqueada. Los enlaces antiguos se recuperan con GET de la misma preferencia; no se crea otra.

Cada pedido conserva una sola preferencia e intento de creación, reforzados con claves únicas y bloqueo del pedido. Se permite reintentar el pago dentro de la misma preferencia. Ante timeout/crash, el intento queda incierto y **no se vuelve a crear la preferencia**; la conciliación busca pagos oficiales. Si no puede recuperarse el identificador de preferencia, queda revisión operativa en el panel, sin segundo POST automático. No se ofrecen reembolsos ni cancelaciones del proveedor. Una vez iniciado un pago se bloquea la cancelación manual del pedido para evitar devolver inventario que aún puede pagarse.

Conciliación: cada minuto, lotes de 5, lease PostgreSQL de 2 minutos, hasta 96 consultas por pago (primeras 12 con intervalo de 30 segundos, después 30 minutos). Los pagos agotados permanecen visibles para revisión; los webhooks posteriores siguen procesándose. Consulta inicial tras 30 segundos. Los recursos se comparan por fecha oficial de actualización; duplicados y snapshots anteriores no repiten transiciones. Disputas/contracargos/estados nuevos pasan a revisión y bloquean el estado PAID. Los eventos guardan únicamente estados técnicos, sin payload personal del proveedor.

Endpoints adicionales: `POST /api/v1/payments/mercadopago/preference` (`{orderId}`, `Idempotency-Key`, CSRF); `GET /api/v1/payments/orders/{orderId}`; `POST /api/v1/webhooks/mercadopago` (firma, única excepción CSRF); `GET /api/v1/admin/payments?page=0`; `GET /api/v1/admin/payments/orders/{orderId}`. Lecturas de cliente requieren propietario, cookie de invitado o `X-Order-Token`; administración requiere ADMIN.

Referencias oficiales: [preferencias](https://www.mercadopago.com.mx/developers/en/docs/checkout-pro-preferences/create-payment-preference), [firma y notificaciones](https://www.mercadopago.com.mx/developers/en/docs/checkout-pro-preferences/payment-notifications), [validador oficial Java](https://github.com/mercadopago/sdk-java/blob/master/src/main/java/com/mercadopago/webhook/WebhookSignatureValidator.java), [compras de prueba](https://www.mercadopago.com.mx/developers/en/docs/checkout-pro-preferences/integration-test/test-purchases). Reembolsos futuros deberán usar la API oficial e idempotencia propia; no hay endpoint de escritura de reembolsos en VELORA.
