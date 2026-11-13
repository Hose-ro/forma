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
Spring Security 6.5.11 también requiere seguimiento del proveedor; VELORA no usa
DPoP, WebAuthn, Authorization Server ni AesBytesEncryptor mencionados en sus avisos.
Revisar soporte/parches de estas líneas antes de producción y antes de agregar esas funciones.

## Requisitos y limitaciones

- Activar únicamente `prod`; HTTPS, `APP_SESSION_SECRET` aleatorio de al menos 32 caracteres
  y `APP_FRONTEND_ORIGIN` con orígenes exactos HTTPS. El perfil prod fuerza cookies Secure.
  Usar TLS verificado para PostgreSQL y ejecutar la validación final en Java 21;
  el entorno de auditoría dispone de JDK 25 y compila con `--release 21`.
- Definir `DATABASE_*` y `DATABASE_MIGRATION_*`. Flyway usa un rol DDL distinto;
  el runtime no puede ser superusuario, crear roles/bases ni omitir RLS. El arranque lo comprueba.
  Provisionar permisos solo en tablas/secuencias de VELORA: SELECT/INSERT/UPDATE/DELETE
  donde corresponda, sin DDL ni propiedad de esquema. Auditoría e historial deben ser
  append-only para el runtime; configurar esos GRANT/REVOKE antes de producción.
- V8 invalida cotizaciones antiguas sin revisión; los pedidos históricos se conservan.
  Probar migraciones en copia de la base y realizar respaldo antes de aplicarlas.
- Rate limiting y sesiones viven en memoria por instancia; reiniciar borra sus contadores/sesiones.
  El proxy Next agrupa clientes bajo su IP: los límites por IP son compartidos, además de los
  límites por cuenta. Configurar límites en el ingreso con IP confiable antes de escalar;
  no habilitar confianza indiscriminada en X-Forwarded-For. Restringir el acceso directo al backend.
- Registro mantiene respuesta 409 para correo existente; revela existencia de cuenta.
  Eliminar esa señal por completo requiere cambiar el flujo actual mediante verificación de correo.
  Login conserva errores genéricos y límites por cuenta/IP. No existe recuperación de contraseña.
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

Las rutas Java de producción parten de `backend/src/main/java/com/velora/`;
las pruebas parten de `backend/src/test/java/com/velora/` y los recursos de `backend/src/main/resources/`.
El directorio backend ya estaba sin seguimiento en Git al comenzar; debe incluirse al versionar el trabajo.
