# Segunda auditoría de Olvenio

Fecha: 16 de noviembre de 2026. Commit revisado: `0056359`.

## Resultado

Las correcciones de la primera auditoría están ampliamente implementadas. Se comprobaron migraciones, permisos de base de datos, navegación móvil y el flujo local de carrito a pedido. El informe anterior describe el estado previo a estas correcciones y ya no representa los pendientes actuales.

Quedan **1 hallazgo P1 y 2 hallazgos P2** en esta revisión. Los dos primeros se reprodujeron con el código actual; el tercero se confirmó por lectura de su ruta de errores. Antes de abrir ventas todavía falta validar los servicios externos y convertir las reproducciones en regresiones automatizadas.

Solo se actualizó documentación del repositorio. Las comprobaciones de datos y pedidos se ejecutaron en una instancia PostgreSQL temporal con datos sintéticos. No se usó la base del negocio, no hubo cobros ni se enviaron correos reales.

## Verificación realizada

| Comprobación | Resultado nuevo |
| --- | --- |
| `npm run typecheck` | Aprobada |
| `npm run test:security` | 2 pruebas aprobadas |
| `mvn test -q` | 60 aprobadas, 3 omitidas en la ejecución estándar |
| Integración `AdminAuditPersistenceIntegrationTest`, activada aparte | 1 aprobada contra PostgreSQL real y rol runtime restringido |
| Resultado combinado del backend | 61 aprobadas, 2 omitidas, sin fallos ni errores |
| `npm audit --json` | 0 vulnerabilidades reportadas por npm |
| `npm run build -- --webpack` | Aprobada; incluye las nuevas rutas reset/verify |
| Flyway sobre una base vacía | V1–V10 aplicadas correctamente en PostgreSQL 14.20 |
| Bootstrap y permisos runtime | Aplicados y comprobados con la integración de persistencia |
| HTTP a través del proxy Next | Carrito, cotización, pedido invitado y cancelación sin pago aprobados |
| Protección contra stock obsoleto por HTTP | Guardar contenido conserva stock descontado; ajuste obsoleto devuelve 409; cancelar restituye unidades |
| Navegador local | Cuenta, pantalla de recuperación, catálogo y ficha conectados; inspección visual en ancho móvil |
| Carrera pago/cancelación | Reproducida con servicios reales de la aplicación y cliente del proveedor simulado |
| Carrera corrección de guía/correo | Reproducida con PostgreSQL real y SMTP simulado |

Las dos integraciones de Mercado Pago siguen omitidas. La prueba PostgreSQL adicional usa una base local nueva, no demuestra una actualización de la base alojada ni una restauración de respaldos. No se volvió a ejecutar el build Turbopack: la evidencia nueva de compilación corresponde a Webpack. Tampoco se realizó una consulta nueva de vulnerabilidades Java.

Para la prueba en navegador se inició la API temporal en el puerto que esperaba el proxy de la compilación local. El fallo inicial de conexión por puertos distintos se resolvió ajustando ese servidor de prueba; no se modificaron archivos de entorno.

## Estado de los hallazgos anteriores

| Hallazgo anterior | Estado actual | Evidencia |
| --- | --- | --- |
| A01: formularios antiguos reponen stock | Corregido para el caso reportado y verificado | DTO con `expectedStock`, endpoint por variante, 3 regresiones nuevas y comprobación HTTP con una compra intercalada |
| A02: reservas y pagos inciertos sin recuperación | Implementado parcialmente; queda B01 | TTL, barrido de reservas, cierre de preferencias, recuperación y conciliación extendida; cancelación sin pago comprobada |
| A03: catálogo real desde cero | Implementado | Categorías, URLs de fotografías, SKU y variantes; categoría/producto sintéticos visibles en catálogo |
| A04: marcar correo como enviado antes de SMTP | Corregido; queda B02 en correcciones concurrentes | Outbox persistente, arrendamiento temporal, reintentos y marca posterior a SMTP |
| A05: límite de IP compartido | Implementado, pendiente de comprobar en el hosting | Cabeceras propias autenticadas por secreto, presupuestos separados por paso; requiere configuración en ambos servicios |
| A06: contraste secundario | Corregido y medido | Nuevo token `#666661`: 5.338:1 sobre canvas y 4.634:1 sobre stone |
| A07: listas truncadas | Corregido en código | Paginación de productos, pedidos y cuenta; agregación de inventario en servidor |
| A08: recuperación y verificación de correo | Implementado; queda B03 y prueba SMTP | Endpoints, tokens de un uso, caducidad, revocación de sesiones y pantallas reset/verify |
| A09: textos live de envío inconsistentes | Corregido en código | Ficha usa reglas del backend; metadatos distinguen el modo de tienda |
| A10: logística sin transiciones válidas | Corregido en código | Flujo permitido, guía/paquetería requeridas, repetición idempotente y corrección explícita |
| A11: controles móviles pequeños | Corregidos los controles principales reportados | Cantidad móvil, iconos y cierre de cookies de 44 px; quedan botones secundarios menores como mejora, sin declarar por ello incumplimiento AA |
| A12: marca anterior | Corregido | Empty usa `o.` y oculta el ornamento al lector de pantalla |

“Implementado” describe el código inspeccionado; las filas aclaran qué se probó en ejecución. No equivale a certificar todos los casos o el despliegue remoto.

## B01 · P1 · Recuperar un pago puede crear una preferencia después de cancelar el pedido

**Ubicación:** `backend/src/main/java/com/olvenio/payments/application/PaymentService.java:85`, en combinación con `ReservationService.release` y `OrderAdminService.cancel`.

La recuperación consulta al proveedor fuera de la transacción. Si no encuentra preferencias, adquiere el bloqueo del pedido, pero **no vuelve a comprobar su estado**: reclama `payment_attempts` y llama a `createPreference`. La cancelación cambia el pedido/pago, pero no invalida ese intento de recuperación. La comprobación en `store`, después del POST al proveedor, llega tarde para impedir la creación externa.

### Reproducción determinista

1. Pedido pendiente con reserva vigente; registro de pago UNKNOWN antiguo, sin URL ni preferencia guardada.
2. Comienza `preference` y pasa la autorización inicial.
3. Durante `preferencesByReference`, se intercala una cancelación que deja el pedido CANCELLED. Esto representa la cancelación administrativa entre la lectura remota y la siguiente transacción de recuperación.
4. La búsqueda devuelve `elements: []` y el intento antiguo sigue siendo reclamable.
5. El código llama a la creación externa con el pedido ya cancelado.
6. `store` detecta CANCELLED y la solicitud local termina en `ProviderException`, pero el POST externo ya ocurrió.

Salida observada, con Mercado Pago simulado:

```text
PROBE payment: external create invoked with order status=CANCELLED
PROBE payment: local request ended with ProviderException
PROBE confirmed: recovery starts external preference creation after local cancellation.
```

**Impacto:** puede quedar una preferencia externa activa para un pedido cuyo inventario ya fue liberado, fuera del estado que la aplicación esperaba. La prueba demuestra la creación indebida; no se hizo ni se afirma haber observado un cobro real.

**Falta aplicar:** coordinar cancelación y recuperación mediante un estado/intento con exclusión y vencimiento. Revalidar pedido, reserva, pago y permiso de reintento al reclamar. La cancelación debe impedir nuevas creaciones y esperar o resolver una creación ya reclamada antes de liberar inventario. Una comprobación aislada antes de la red no basta si la cancelación puede intercalarse después.

**Validación requerida:** regresión que intercale cancelación antes de reclamar y otra durante la creación externa; nunca debe quedar un enlace cobrable después de liberar la reserva.

## B02 · P2 · El correo anterior puede consumir la notificación de una guía corregida

**Ubicación:** `backend/src/main/java/com/olvenio/orders/infrastructure/OrderMailer.java:99` y `backend/src/main/java/com/olvenio/orders/application/OrderAdminService.java:48`.

Corregir una guía reutiliza la misma fila de outbox, poniéndola PENDING. El envío que ya estaba en curso termina actualizando esa fila por su id, sin comprobar si cambió la guía o la versión del trabajo.

### Reproducción con PostgreSQL real y SMTP simulado

1. Un trabajo SHIPMENT está PROCESSING y ha preparado un mensaje con `OLD-TRACKING`.
2. Mientras se realiza `mail.send`, se aplica la corrección: la orden usa `NEW-TRACKING` y el trabajo vuelve a PENDING para notificarla.
3. El envío viejo termina y escribe SENT en esa misma fila y `shipping_email_sent_at` en la orden.
4. El destinatario recibió la guía vieja; la notificación nueva ya no queda pendiente.

La prueba ejecutó `OrderMailer.send` sobre la base temporal y reprodujo la intercalación SQL de la corrección. Resultado observado: mensaje con guía vieja, orden con guía nueva y trabajo SENT.

```text
PROBE mail: administrator requeued NEW-TRACKING while worker sent OLD-TRACKING
PROBE confirmed: old worker leaves outbox SENT; corrected tracking notification is no longer pending.
```

**Impacto:** corregir la logística puede dejar al cliente con instrucciones de seguimiento equivocadas sin recibir el aviso de corrección.

**Falta aplicar:** identificar cada reclamación y revisión del contenido. Finalizar únicamente el trabajo y la revisión que realmente se enviaron; una corrección concurrente debe conservar su propio trabajo pendiente. Usar una fila/evento nuevo por revisión o actualización condicional con versión, más una regresión que intercale ambas operaciones.

## B03 · P2 · Los correos de recuperación siguen sin entrega recuperable

**Ubicación:** `backend/src/main/java/com/olvenio/identity/application/AccountRecoveryService.java:51`, `:134` y `:150`.

Los correos de pedidos usan una cola persistente, pero recuperación y verificación usan una cola de proceso con `DiscardPolicy`. Se invalidan los enlaces anteriores y se persiste el token nuevo antes de enviar; si SMTP falla, `guarded` solo registra el fallo. No queda un envío durable pendiente. Un reinicio pierde los trabajos en memoria y una cola llena los descarta silenciosamente, aunque la solicitud ya respondió 202.

**Impacto:** el usuario puede quedarse sin el enlace esperado, e incluso perder un enlace anterior válido al solicitar otro que nunca se entrega. Tiene que volver a solicitarlo y queda sujeto a los límites de envío.

**Falta aplicar:** entrega persistente con reintentos, caducidad y observabilidad para estos mensajes, conservando la respuesta pública genérica. El token en claro no debe quedar expuesto en una cola sin protección; diseñar el almacenamiento protegido o la generación coordinada del enlace.

Este hallazgo se confirmó inspeccionando la ruta de fallo; no se enviaron correos ni se saturó el servidor para reproducirlo.

## Qué falta validar antes de producción

- **Regresiones de las nuevas funciones.** Las 3 pruebas nuevas cubren stock; no hay pruebas específicas de los nuevos servicios de reservas, outbox y recuperación de cuenta. Incorporar B01/B02 y escenarios de caducidad, token gastado/vencido, revocación y reinicio.
- **CI.** No hay configuración de integración continua en el repositorio. Ejecutar automáticamente tipos, build y pruebas; añadir PostgreSQL temporal para los límites transaccionales.
- **Mercado Pago real de pruebas.** Ejecutar las 2 integraciones omitidas y verificar vencimiento, recuperación de timeout, cancelación con preferencia, pagos offline, duplicados y pagos tardíos. La documentación oficial trata por separado la [vigencia de la preferencia](https://www.mercadopago.com.mx/developers/es/docs/checkout-pro-preferences/additional-settings/term-of-preference) y el [vencimiento de pagos offline](https://www.mercadopago.com.mx/developers/es/docs/checkout-pro-preferences/additional-settings/expiration-date); comprobar ambos y el plazo comercial elegido, no solo que el JSON compile.
- **Correo.** Entrega real de confirmación, envío, guía corregida, reset y verificación; alertas y procedimiento para mensajes FAILED.
- **Proxy alojado.** Mismo secreto robusto en ambos servicios y cabecera de IP garantizada por el hosting; comprobar límites independientes desde dos redes y rechazo de cabeceras falsificadas. El cambio está implementado, su configuración remota no se inspeccionó.
- **Operación.** Datos del negocio, catálogo, cobertura, dominios, respaldos/restauración, monitoreo y revisión profesional de políticas. Los permisos y migraciones locales no demuestran que estén aplicados en Neon/Render.
- **Interfaz.** Revisar escritorio y móvil con teclado, lector de pantalla y una herramienta de accesibilidad. Se inspeccionó visualmente una parte de la tienda; no es una certificación WCAG ni una medición de Core Web Vitals.

## Orden recomendado

1. Resolver B01 antes de activar cobros reales y conservar la reproducción como regresión.
2. Resolver B02/B03 y validar recuperación/entrega con el proveedor de correo.
3. Agregar CI y completar las pruebas de Mercado Pago, vencimientos y límites en staging.
4. Hacer una pasada de `impeccable harden` en los estados de error y terminar con `impeccable polish`; repetir la auditoría tras los cambios.

## Qué ya está bien

La corrección de stock protege el caso original también por HTTP y PostgreSQL; el catálogo se administra con categorías y variantes propias; la guía de producción distingue lo implementado de lo pendiente de servicios externos. Se mantienen autorización de recursos, CSRF, precios calculados en servidor y verificación del proveedor. Los hallazgos nuevos se concentran en coordinar trabajos que ocurren al mismo tiempo y en la durabilidad del correo de identidad.
