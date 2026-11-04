# Brief para Claude: repo `servired-moderacion`

> Lee este documento completo antes de tocar nada. Guárdalo en la raíz del repo como `CLAUDE.md`.
> No tienes acceso al documento original del proyecto (SAD); todo lo que necesitas está aquí. Si algo falta o se contradice, **pregunta antes de inventar**.
> **Fuente de verdad:** `servired-infra/contracts` (v0.1.0). Si este brief y los contratos difieren, **gana el contrato**: avísame de la diferencia.

---

## 1. Contexto del proyecto

**SERVI·RED** es una plataforma colaborativa de reporte y monitoreo de servicios públicos domiciliarios en Colombia (energía, gas, acueducto, alcantarillado, aseo). Proyecto del curso *Arquitectura de Software* (Pontificia Universidad Javeriana, equipo de 3 personas). Los ciudadanos reportan cortes, restablecimientos y **conexiones fraudulentas** desde una app móvil.

Estilo: **microservicios**, una base de datos por servicio, comunicación **híbrida**: asíncrona por bus de eventos, y **solo dos llamadas síncronas, las dos nacen en este servicio**.

### Repos (organización GitHub `ProyectoArquisoft`, clonados lado a lado)

| Repo | Estado | Rol |
|---|---|---|
| `servired-infra` | contratos v0.1.0 listos; compose solo con `mock` | OpenAPI, esquemas de eventos, compose, scripts de Supabase |
| `servired-gateway` | por crear | Entrada única (:8080); enruta; protege el anonimato de `/api/fraudes` |
| `servired-reportes` | vacío | Spring Boot (:8081); te responde `GET /internal/reportes/{id}` |
| **`servired-moderacion`** | **este repo (vacío)** | Spring Boot (Java), puerto **8082** |
| `servired-indicadores` | **ya implementado (.NET 10)** | Te responde `/internal/indicadores/patrones`; consume tus eventos |
| `servired-app` | M0 listo | App Flutter del ciudadano |

**No hay panel web.**

### Decisiones ya tomadas (no las cuestiones)

- **D2** BD por servicio: tu esquema es `moderacion`. `id_reporte_ref` e `id_servicio` son referencias **lógicas** (sin llave foránea).
- **D3** Las dos únicas llamadas síncronas del sistema: (a) a Reportes para verificar que el reporte referenciado exista; (b) a Indicadores para contrastar el patrón histórico de la zona. La decisión de aprobar o rechazar no puede basarse en información potencialmente desactualizada.
- **Anonimato verificable (la restricción que define este servicio):** Moderación **nunca recibe ni guarda identidad del usuario**.
- Datos en **PostgreSQL de Supabase**. Bus: **RabbitMQ**; tú **publicas**, nunca consumes.

---

## 2. Tu responsabilidad

Recibir denuncias anónimas de conexiones fraudulentas, decidir (aprobar / rechazar / dejar para revisión), guardar la decisión y publicar el evento correspondiente.

| Método | Ruta | RF | Acceso |
|---|---|---|---|
| POST | `/api/fraudes` | RF4 / RF5 | **Sin autenticación (anónimo)** |
| GET | `/actuator/health` | – | healthcheck del compose |

Contrato exacto: `servired-infra/contracts/openapi/moderacion.yaml` (que reutiliza `/api/fraudes` de `servired-public.yaml`).

**Petición** `NuevoFraude` (JSON, `additionalProperties:false`): `idServicio` (int64), `lat`, `lng`, `descripcion` (1–1000 caracteres), `idReporteRef?` (int64, nullable). Header `Idempotency-Key` opcional.
**Respuesta** `201`: `{ idFraude, resultado }` con `resultado` ∈ `APROBADO | RECHAZADO | PENDIENTE_REVISION`. Errores: `400` (RFC 7807, con `errores:[{campo,mensaje}]`), `429` (lo genera el gateway), `500`.

---

## 3. Stack propuesto

- **Java 21 (LTS)**, **Spring Boot** estable reciente, **Maven** con wrapper.
- Spring Web, Validation, Spring AMQP, Actuator, **Flyway**, driver PostgreSQL, `RestClient` para las llamadas síncronas.
- **No incluyas Spring Security**: este servicio no autentica a nadie, y menos librerías de sesión o de *request cache* que puedan retener datos del cliente.
- Acceso a datos: `JdbcClient`/SQL explícito (⚠ propuesta; JPA también sirve, es una tabla simple).
- Pruebas: JUnit 5, Testcontainers (Postgres y RabbitMQ), WireMock (Reportes e Indicadores), `OutputCaptureExtension` (para verificar logs), `json-schema-validator` (eventos contra los esquemas de infra).
- Paquete base: `com.servired.moderacion` (`api`, `dominio`, `reglas`, `persistencia`, `clientes`, `eventos`, `config`).

---

## 4. Configuración (mismas variables que pone el compose de infra)

| Variable | Obligatoria | Notas |
|---|---|---|
| `DB_URL` | Sí | JDBC, p. ej. `jdbc:postgresql://db.<proyecto>.supabase.co:5432/postgres?sslmode=require` |
| `DB_USER` / `DB_PASSWORD` | Sí | Rol `svc_moderacion` |
| `DB_SCHEMA` | No | Por defecto `moderacion` |
| `RABBITMQ_URL` | Sí | `amqp://guest:guest@rabbitmq:5672` |
| `REPORTES_BASE_URL` | Sí | `http://reportes:8081` |
| `INDICADORES_BASE_URL` | Sí | `http://indicadores:8083` |
| `SYNC_TIMEOUT_MS` | No | `2000` (por llamada) |
| `MODERACION_REGLAS_*` | No | Umbrales de las reglas (§7) |

Puerto `8082`. Con `.env.example` (sin secretos). **No hay variables de Supabase Auth**: este servicio no valida JWT.

---

## 5. Anonimato: requisitos duros y cómo comprobarlos

El gateway ya elimina `Authorization`, cookies, IP, `Forwarded`/`X-Forwarded-*` y cualquier identificador de dispositivo antes de reenviar. **Aun así, este servicio debe comportarse como si algo se hubiera escapado** (defensa en profundidad):

1. **No leas ni uses** ningún header de identidad (`Authorization`, `X-Forwarded-For`, `User-Agent`, etc.). Si llegan, se ignoran.
2. **Esquema sin identidad:** ninguna tabla ni columna puede guardar usuario, IP, dispositivo ni `User-Agent`. Una prueba consulta `information_schema.columns` del esquema `moderacion` y falla si encuentra nombres como `usuario`, `user`, `ip`, `device`, `agent`, `token`.
3. **Logs:** desactiva el *access log* de Tomcat y no registres la IP remota, headers ni el cuerpo de `/api/fraudes` (la `descripcion` es texto libre y puede contener datos personales). `server.forward-headers-strategy=none`. Una prueba hace una petición con `Authorization`, `X-Forwarded-For` y `User-Agent` de prueba y verifica, con `OutputCaptureExtension`, que esos valores **no aparecen** en la salida.
4. **Eventos:** `fraude.validado` y `fraude.rechazado` no pueden llevar ningún campo de usuario (sus esquemas tienen `additionalProperties:false`; una prueba lo verifica).
5. `Idempotency-Key`: si llega, es un UUID aleatorio sin relación con ninguna cuenta; solo se usa para no duplicar un reintento (§6).
6. El *rate limiting* es del gateway y no se persiste en ningún servicio de negocio. Aquí no se cuenta nada por IP.

---

## 6. Modelo de datos y migraciones (Flyway, esquema `moderacion`)

Modelo del SAD (`snake_case`), con adiciones marcadas ⚠:

- `reporte_fraude(id_fraude, id_reporte_ref [sin FK], id_servicio [referencia lógica], ubicacion_lat, ubicacion_lng, descripcion, fecha_creacion)`
  + ⚠ `idempotency_key UUID UNIQUE NULL` (UUID aleatorio del cliente, sin vínculo con identidad; permite que un reintento desde la cola offline de la app no duplique la denuncia).
- `decision_moderacion(id_decision, id_fraude→reporte_fraude, resultado, justificacion, fecha_decision)`; `resultado` ∈ `APROBADO | RECHAZADO | PENDIENTE_REVISION`.
- ⚠ `outbox_evento(id_evento uuid, tipo, payload jsonb, correlation_id, creado, publicado_en null)` (§9).

El rol `svc_moderacion` necesita `CREATE` en su esquema para que Flyway migre.

---

## 7. Flujo de decisión (`POST /api/fraudes`)

```
validar (400)  ──▶  ¿mismo idempotency_key ya guardado?  ──sí──▶  devolver la misma respuesta
      │ no
      ▼
llamadas síncronas, EN PARALELO si aplican (timeout SYNC_TIMEOUT_MS, 1 reintento cada una):
   (a) si hay idReporteRef: GET {REPORTES}/internal/reportes/{id}
   (b) siempre:             GET {INDICADORES}/internal/indicadores/patrones?idServicio&lat&lng
      │
      ├─ alguna falla por timeout/5xx tras el reintento ──▶ PENDIENTE_REVISION
      ▼
cadena de reglas ──▶ resultado + justificación
      ▼
una transacción: insertar reporte_fraude + decision_moderacion + outbox_evento (si aplica)
      ▼
201 { idFraude, resultado }
```

### Contratos de las dos consultas (ver `reportes.yaml` e `indicadores.yaml`)
- Reportes → `{ existe, idServicio, estado, lat, lng }` (siempre `200`; si no existe, `existe=false` y el resto `null`).
- Indicadores → `{ idServicio, radioMetros, ventanaDias, fraudesValidados, fraudesRechazados, cortesReportados }`.

Usa *timeouts* de conexión y de lectura de `SYNC_TIMEOUT_MS`, **un solo reintento** y ninguno si el error es `4xx`. Ejecutarlas en paralelo mantiene el peor caso por debajo de ~4 s (el gateway permite hasta ~15 s en esta ruta).

### Reglas (cadena de responsabilidad; cada regla se puede activar/desactivar y parametrizar)

⚠ Son **propuestas** para confirmar con el equipo: el SAD solo dice "aplica validaciones automáticas y, cuando es necesario, consulta a otros servicios". Impleméntalas como estrategias independientes para poder cambiarlas fácil:

| # | Regla | Resultado |
|---|---|---|
| 1 | Si hay `idReporteRef` y `existe=false` | `RECHAZADO` ("El reporte referenciado no existe") |
| 2 | Si hay `idReporteRef` y su `idServicio` ≠ el de la denuncia | `RECHAZADO` ("El servicio no coincide con el reporte referenciado") |
| 3 | Si hay `idReporteRef` y la distancia (haversine) entre el punto y el reporte supera `MODERACION_REGLAS_DISTANCIA_MAX_METROS` (2000) | `RECHAZADO` ("Ubicación incoherente") |
| 4 | Si la descripción tiene menos de `MODERACION_REGLAS_DESCRIPCION_MIN` (10) caracteres alfanuméricos | `RECHAZADO` ("Descripción insuficiente") |
| 5 | Si `fraudesRechazados ≥ MODERACION_REGLAS_UMBRAL_RECHAZOS` (5) y `fraudesValidados = 0` en la zona | `PENDIENTE_REVISION` ("Zona con historial de denuncias rechazadas") |
| 6 | En cualquier otro caso | `APROBADO` |

La primera regla que decide corta la cadena. La `justificacion` se guarda en `decision_moderacion` (la respuesta al ciudadano solo lleva `idFraude` y `resultado`).
⚠ Hasta que el contrato v0.2.0 agregue `lat/lng` a `fraude.rechazado`, Indicadores devuelve siempre `fraudesRechazados=0`: la regla 5 quedará inactiva en la práctica. No es un error tuyo.

### Notificación al operador (fuera del MVP)
El diseño prevé avisar al operador cuando un fraude se aprueba, mediante un canal externo (SMS/WhatsApp). Déjalo como una interfaz `NotificadorOperador` con una implementación que solo escribe un log **sin datos personales**. No integres ningún proveedor.

---

## 8. Cliente HTTP y tolerancia a fallos

- `RestClient` con *timeouts* configurados, un reintento y mapeo de errores a un resultado interno (`Fallo`), nunca a una excepción hacia el ciudadano.
- Un fallo de Reportes o de Indicadores **no** debe producir un `500`: produce `PENDIENTE_REVISION` (es el comportamiento de respaldo del riesgo R3 del SAD: fallos en cascada).
- No propagues ningún header de la petición entrante a esas llamadas (en especial, ninguno de identidad).
- Registra solo el resultado (`consulta Reportes: timeout`), sin el contenido de la denuncia.

---

## 9. Eventos que publicas (RabbitMQ)

- Exchange **`servired.events`**, tipo `topic`, `durable=true`, `autoDelete=false`. Routing key = `type`. Declara el exchange al arrancar (idempotente) con **exactamente esos argumentos** (si difieren de los de infra, RabbitMQ responde `PRECONDITION_FAILED`). **No declares ni consumas colas.**
- Mensajes `application/json`, persistentes, `messageId = eventId`, *publisher confirms*.
- **Outbox transaccional** ⚠ (propuesta): el evento se escribe en `outbox_evento` en la misma transacción que la decisión y un publicador `@Scheduled` lo envía y marca `publicado_en`. Indicadores es idempotente por `eventId`.
- Sobre: `{ eventId (uuid), type, version:1, occurredAt (UTC), source:"moderacion", correlationId (uuid), data }`. `correlationId`: un UUID nuevo por denuncia (no uses nada que identifique al cliente).
- **`data` tiene `additionalProperties:false`: envía exactamente estos campos.**

| Resultado | Evento | `data` |
|---|---|---|
| `APROBADO` | `fraude.validado` | `idFraude, idServicio, lat, lng, fecha` |
| `RECHAZADO` | `fraude.rechazado` | `idFraude, idServicio, fecha` |
| `PENDIENTE_REVISION` | **ninguno** (el contrato v0.1.0 no define evento) | – |

Valida cada payload en las pruebas contra el JSON Schema de infra (copia versionada en `src/test/resources/contratos/`, anotando la versión del contrato).

---

## 10. Pruebas, Docker y CI

- **Obligatorias:** integración con Postgres y RabbitMQ reales (Testcontainers; si Docker no está, se saltan, como en Indicadores); cada regla por separado y la cadena completa; fallo/timeout de Reportes o de Indicadores → `PENDIENTE_REVISION` (con WireMock con retardo); idempotencia (reintento = misma respuesta, un solo evento); los **cuatro chequeos de anonimato** del §5 (esquema, logs, headers ignorados, eventos); contrato de eventos contra los esquemas; `400` con `errores[]`.
- **Dockerfile** multi-etapa (JDK 21 → JRE), usuario sin privilegios, puerto 8082, `HEALTHCHECK` contra `/actuator/health` (instala `curl`, como hizo Indicadores).
- **CI** `.github/workflows/ci.yml`: job `test` (`mvn verify`) en cada push y PR; job `imagen` en `main` que publica `ghcr.io/proyectoarquisoft/servired-moderacion` con tags `<sha>` y `latest` (nombre en minúsculas; usa el workflow de `servired-indicadores` como modelo).
- **README** con variables, cómo correr local, tabla de reglas y la sección "Garantías de anonimato" con cómo se verifica cada una.

---

## 11. Hitos (en este orden, commits pequeños)

Antes de empezar, escribe un plan corto y confírmalo conmigo.

| Hito | Entrega | Aceptación |
|---|---|---|
| **M0** | Proyecto base, `.env.example`, Dockerfile, CI, README, manejo de errores RFC 7807, logs sin identidad | Compila, CI verde, `/actuator/health` responde, prueba de logs en verde |
| **M1** | Flyway (V1) y `POST /api/fraudes` solo con validación y las reglas que no necesitan consultas externas (4 y 6) | Guarda `reporte_fraude` y `decision_moderacion`; la prueba del esquema sin identidad pasa |
| **M2** | Clientes de Reportes e Indicadores (paralelo, *timeout*, reintento) + reglas 1, 2, 3, 5 + `PENDIENTE_REVISION` ante fallos | Con WireMock caído o lento, responde `201 PENDIENTE_REVISION`, nunca `500` |
| **M3** | Outbox + publicador + `fraude.validado` / `fraude.rechazado` | Los payloads validan contra los esquemas y llegan a Indicadores |
| **M4** | Idempotencia con `idempotency_key` | Reintento = misma respuesta, un solo evento |
| **M5** | Pulido: pruebas de anonimato completas, README, imagen en GHCR | Cumple §10 |

---

## 12. Reglas de trabajo

- No modifiques otros repos ni el contrato. Si necesitas un cambio (por ejemplo, agregar `lat/lng` a `fraude.rechazado`), descríbemelo y lo gestiono en `servired-infra` (cambia versión y `CHANGELOG`).
- Ningún secreto en git. Documentación en español; commits convencionales; trabaja en ramas y PR (`main` protegida).
- No agregues tecnologías fuera de este brief sin preguntar. En particular, **nada de Spring Security, analítica ni librerías que capturen datos del cliente**.

---

## 13. Preguntas abiertas (resuélvelas conmigo, no por tu cuenta)

1. ⚠ **Reglas de decisión** (§7): ¿las aprobamos tal cual, con qué umbrales y cuáles se apagan?
2. ⚠ **Zona de los fraudes:** `fraude.validado` solo trae `lat/lng`, por lo que no hay filas `FRAUDE` por municipio en Indicadores. La propuesta es agregar `departamento`/`municipio` (o resolverlos en Reportes/Moderación) en el contrato v0.2.0.
3. ⚠ **`fraude.rechazado` sin ubicación:** pedir `lat/lng` en v0.2.0 para que `fraudesRechazados` funcione.
4. ⚠ Columna `idempotency_key` y tabla `outbox_evento` fuera del modelo del SAD: documentarlas en `servired-infra/docs/data-model.md`.
5. ⚠ ¿Hace falta un endpoint interno para la **revisión manual** de los `PENDIENTE_REVISION`? Hoy no existe en el contrato (queda fuera del MVP).
6. ⚠ ¿Outbox o publicación directa? ¿JDBC puro o JPA?
