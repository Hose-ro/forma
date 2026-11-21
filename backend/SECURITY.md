# Seguridad y puesta en producción

Cambios revisados el 12 de noviembre de 2026. No se modificó infraestructura ni se habilitaron pagos reales.

| Severidad | Evidencia y resolución |
| --- | --- |
| HIGH | `CheckoutService`: varias cotizaciones del mismo carrito podían reservar inventario con claves distintas. V8 agrega revisión del carrito y unicidad; la confirmación bloquea carrito/pedido y rechaza revisiones consumidas o modificadas. |
| HIGH | Login, registro y escrituras públicas sin límites; JSON sin cota de lectura. Límites acotados por IP/cuenta y cuerpos de hasta 128 KiB, incluso sin Content-Length. |
| MEDIUM | `Account`: comprobación de redirección aceptaba `/\\host`. Canonicalización y rechazo de autoridades, controles y barras invertidas, incluso codificados. |
| MEDIUM | Sesión de siete días, cookies inseguras por defecto y errores SQL completos en logs. Expiración inactiva de 30 minutos, Secure por defecto y logs sin mensajes de excepciones ni valores SQL. |
| LOW | Secreto de desarrollo conocido y documentación API pública por defecto. Se elimina el fallback; OpenAPI queda limitado a desarrollo. |

## Dependencias

`npm audit`: cero avisos. Dependencias npm fijadas a las versiones del lockfile.
Spring Boot 3.5.16 con parches compatibles: Tomcat 10.1.59, Jackson 2.21.7,
PostgreSQL JDBC 42.7.12, Commons Lang 3.18.0 y Log4j 2.25.5.
Consulta OSV sobre dependencias runtime y revisión de avisos oficiales de Spring.

Inventario pendiente: Spring MVC 6.2.19 aparece en [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) (XsltView)
y [CVE-2026-47890](https://spring.io/security/cve-2026-47890/) (SSE con fragmentos). No hay vistas XSLT, mapeos de vistas `/**`,
SSE ni fragmentos de vistas en este backend REST. Los avisos consultados ofrecen
parches OSS en Framework 7.0.9; no se fuerza una migración incompatible de Spring Boot.
Spring Security 6.5.11 también requiere seguimiento del proveedor; Olvenio no usa
DPoP, WebAuthn, Authorization Server ni AesBytesEncryptor mencionados en sus avisos.
Revisar soporte/parches de estas líneas antes de producción y antes de agregar esas funciones.

## Requisitos y limitaciones

- Activar únicamente `prod`; HTTPS, `APP_SESSION_SECRET` aleatorio de al menos 32 caracteres
  y `APP_FRONTEND_ORIGIN` con orígenes exactos HTTPS. El perfil prod fuerza cookies Secure.
  Usar TLS verificado para PostgreSQL y ejecutar la validación final en Java 21;
  el entorno de auditoría dispone de JDK 25 y compila con `--release 21`.
- Definir `DATABASE_*` y `DATABASE_MIGRATION_*`. Flyway usa un rol DDL distinto;
  el runtime no puede ser superusuario, crear roles/bases ni omitir RLS. El arranque lo comprueba.
  Provisionar permisos solo en tablas/secuencias de Olvenio: SELECT/INSERT/UPDATE/DELETE
  donde corresponda, sin DDL ni propiedad de esquema. Auditoría e historial deben ser
  append-only para el runtime; configurar esos GRANT/REVOKE antes de producción.
- V8 invalida cotizaciones antiguas sin revisión; los pedidos históricos se conservan.
  Probar migraciones en copia de la base y realizar respaldo antes de aplicarlas.
- Rate limiting y sesiones viven en memoria por instancia; reiniciar borra sus contadores/sesiones.
  Con `APP_PROXY_SECRET` (= `BACKEND_PROXY_SECRET` del frontend) el límite usa la dirección del visitante que
  informa el proxy Next en `X-Olvenio-Client-Ip`; esa cabecera solo se acepta junto al secreto, así que quien
  llegue directo al backend no puede falsificarla y queda limitado por su dirección de transporte.
  `X-Forwarded-For` sigue sin confiarse. Sin el secreto, los clientes del proxy comparten un único límite.
  Pendiente de validar con varios clientes en el hosting real.
- Registro mantiene respuesta 409 para correo existente; revela existencia de cuenta (limitado a 5 intentos
  por 15 min). Eliminar esa señal exige que el registro deje de iniciar sesión de inmediato.
  Login conserva errores genéricos y límites por cuenta/IP.
- Recuperación de contraseña y verificación de correo: enlaces de un solo uso (30 min / 48 h), solo se guarda
  el HMAC del token, la respuesta es idéntica exista o no la cuenta, y restablecer la contraseña cierra las
  sesiones abiertas (registro en memoria, igual que las sesiones). La verificación no bloquea compras.
  No hay MFA para administración.
- La CSP usa nonces y renderizado dinámico; mantiene estilos inline para Motion/GSAP.
  Las imágenes remotas solo se optimizan desde `IMAGE_ALLOWED_HOSTS` explícitos.
- No se detectaron credenciales reales en el código/historial accesible (un commit);
  `backend/.env` permanece ignorado y no se imprimieron sus valores. Rotar cualquier secreto
  previamente compartido fuera del repositorio; quitar archivos no borra el historial.
- Mercado Pago ya existía y permanece limitado a pruebas. No se implementó CJdropshipping.
  Webhook conserva firma HMAC, consulta oficial del recurso y deduplicación transaccional.
  No hay ventana temporal de rechazo de firmas antiguas; una repetición firmada puede
  provocar consultas al proveedor (el límite y la deduplicación evitan cambiar dos veces el pedido).
  Revisar política de reintentos/frescura con el proveedor antes de habilitar pagos reales.

## Detección de secretos

- `gitleaks` (config en `.gitleaks.toml`) revisa los cambios preparados antes de cada commit y los commits
  nuevos antes de cada push (`.githooks/`). `npm install` activa los hooks (`core.hooksPath`); requiere
  `brew install gitleaks`. Escaneo manual del historial: `npm run scan:secrets`.
- El workflow `.github/workflows/secrets.yml` escanea todo el historial en cada push y pull request.
- GitHub tiene activados el escaneo de secretos y la protección al hacer push (solo patrones de proveedores).
- Los valores falsos de pruebas van en la lista permitida de `.gitleaks.toml`; nunca permitir una ruta completa.
- El repositorio es público: un secreto que llegue a GitHub se considera filtrado aunque se borre después.

## Rotación de secretos

Si un secreto sale de tu máquina (commit, captura, chat, paquete npm sospechoso), rótalo primero y limpia
después: borrar el archivo o reescribir el historial no lo vuelve secreto. Staging y producción nunca
comparten valores, así que rota en el entorno afectado (Render `render.yaml` / `render.production.yaml`,
Vercel y Neon de cada uno).

| Secreto | Dónde se genera | Dónde se actualiza | Efecto al rotar |
| --- | --- | --- | --- |
| `MERCADOPAGO_ACCESS_TOKEN` | Mercado Pago → Tus integraciones → Credenciales → renovar | Render | Ninguno visible; redeploy |
| `MERCADOPAGO_WEBHOOK_SECRET` | Mercado Pago → Webhooks → regenerar clave secreta | Render | Las notificaciones firmadas con la clave vieja se rechazan; el proveedor reintenta |
| `PAYPAL_CLIENT_SECRET` | PayPal Developer → la app → generar secreto nuevo y borrar el viejo | Render | Ninguno visible; redeploy |
| `DATABASE_PASSWORD`, `DATABASE_MIGRATION_PASSWORD` | Neon → Roles → restablecer contraseña | Render (y `DATABASE_URL` si la incluye) | Las conexiones viejas fallan hasta el redeploy |
| `APP_SESSION_SECRET` | Render → generar valor (mínimo 32 caracteres aleatorios) | Render | Cierra todas las sesiones e invalida enlaces pendientes de recuperación/verificación |
| `APP_PROXY_SECRET` / `BACKEND_PROXY_SECRET` | `openssl rand -base64 32` | Render **y** Vercel con el mismo valor | Mientras no coincidan, el límite por visitante cae al límite compartido del proxy |
| `SPRING_MAIL_PASSWORD` | Proveedor SMTP → nueva contraseña de aplicación y revocar la vieja | Render | Los correos se reintentan desde la cola tras el redeploy |
| Contraseña del admin | Desde la tienda: "Olvidé mi contraseña" con `APP_ADMIN_EMAIL` | — | Cierra sus sesiones. Cambiar `APP_ADMIN_PASSWORD` no sirve: solo se usa al crear el admin |

Después de rotar: redeploy, prueba un pago de prueba y un correo, revisa en el panel de cada proveedor que no
haya uso del valor viejo y actualiza tu `backend/.env` / `.env.staging.local` local.

## Archivos de esta revisión

Rutas relativas a la raíz del proyecto; `.env` solo cambia de permisos a 0600.

| Área | Archivos |
| --- | --- |
| Raíz | `.env.example`, `next.config.mjs`, `package.json`, `package-lock.json` |
| Frontend | `src/proxy.ts`, `src/lib/safe-redirect.ts`, `src/app/layout.tsx`, `src/components/account.tsx`, `src/lib/api/http.ts`, `src/lib/api/server.ts`, `tests/security.test.mjs` |
| Backend | `backend/.env`, `backend/.env.example`, `backend/.gitignore`, `backend/pom.xml`, `backend/README.md`, `backend/SECURITY.md` |
| Configuración Java | `shared/config/SecurityConfig.java`, `shared/config/AppProperties.java`, `shared/config/ProductionSecurityCheck.java` |
| Seguridad Java | `shared/security/ApiRequestFilter.java`, `shared/security/RequestLimits.java`, `shared/security/GuestSession.java`, `shared/security/ResourceAccess.java`, `shared/domain/RateLimitException.java` |
| API compartida | `shared/api/GlobalExceptionHandler.java`, `shared/api/PageRequests.java` |
| Identidad | `identity/api/AuthController.java`, `identity/application/IdentityDtos.java`, `identity/infrastructure/DevAdminInitializer.java` |
| Catálogo | `catalog/application/CatalogDtos.java`, `catalog/application/CatalogQueryService.java`, `catalog/application/CatalogAdminService.java` |
| Comercio | `cart/domain/Cart.java`, `cart/infrastructure/CartRepository.java`, `cart/application/CartService.java`, `orders/domain/Order.java`, `checkout/application/CheckoutService.java` |
| Recursos | `backend/src/main/resources/application.yml`, `application-dev.yml`, `application-prod.yml`, `db/migration/V8__security_integrity.sql` |
| Pruebas Java | `shared/ResourceAccessTest.java`, `shared/SecurityRegressionTest.java`, `shared/RequestLimitsTest.java`, `shared/ResourceBoundaryTest.java`, `shared/ProductionSecurityCheckTest.java`, `orders/IdempotencyTest.java` |

Las rutas Java de producción parten de `backend/src/main/java/com/olvenio/`;
las pruebas parten de `backend/src/test/java/com/olvenio/` y los recursos de `backend/src/main/resources/`.
El directorio backend ya estaba sin seguimiento en Git al comenzar; debe incluirse al versionar el trabajo.
