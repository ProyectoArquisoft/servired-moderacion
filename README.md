# servired-moderacion

Servicio de SERVI·RED que recibe denuncias anónimas de conexiones fraudulentas, decide si las aprueba, las rechaza o las deja para revisión, y publica el evento correspondiente.

**Estado: M0.** Ya están el proyecto base, el manejo de errores, los logs sin identidad, el Dockerfile y el CI. `POST /api/fraudes` llega en M1.

- Puerto: `8082`
- Contrato: `servired-infra/contracts/openapi/moderacion.yaml` (v0.1.0)
- Stack: Java 21, Spring Boot 4.1, Maven (con wrapper)

## Variables

Copia [.env.example](.env.example) para ver los valores de ejemplo.

| Variable | Obligatoria | Desde | Notas |
|---|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Sí | M1 | JDBC de Supabase; rol `svc_moderacion` |
| `DB_SCHEMA` | No | M1 | Por defecto `moderacion` |
| `RABBITMQ_URL` | Sí | M3 | `amqp://guest:guest@rabbitmq:5672` |
| `REPORTES_BASE_URL` | Sí | M2 | `http://reportes:8081` |
| `INDICADORES_BASE_URL` | Sí | M2 | `http://indicadores:8083` |
| `SYNC_TIMEOUT_MS` | No | M2 | `2000` |
| `MODERACION_REGLAS_*` | No | M1 y M2 | Umbrales de las reglas |

En M0 el servicio arranca sin ninguna variable.

## Correr en local

Requiere JDK 21.

```sh
./mvnw spring-boot:run           # en Windows: mvnw.cmd spring-boot:run
curl http://localhost:8082/actuator/health
```

## Pruebas

```sh
./mvnw verify
```

## Imagen

```sh
docker build -t servired-moderacion .
docker run --rm -p 8082:8082 servired-moderacion
```

El CI publica la imagen en `ghcr.io/proyectoarquisoft/servired-moderacion` con los tags `<sha>` y `latest` en cada push a `main`.

## Errores

Todos los errores salen en RFC 7807 (`application/problem+json`). Las validaciones agregan `errores: [{ campo, mensaje }]`.

Los mensajes de las excepciones pueden contener partes de la petición, así que nunca se copian: las respuestas usan textos fijos y el log solo registra la clase de la excepción.

## Garantías de anonimato

Moderación nunca recibe ni guarda la identidad del usuario. Esta tabla crece con cada hito.

⚠ No subas `org.springframework.web` a `DEBUG` fuera de una depuración local: con ese nivel, Spring registra los mensajes de las excepciones, que pueden contener partes de la petición.

| Garantía | Cómo se cumple | Cómo se verifica |
|---|---|---|
| Los headers de identidad no llegan a los logs | Ningún código los lee; `log-request-details` apagado | `LogsSinIdentidadTest` envía `Authorization`, `X-Forwarded-For`, `User-Agent` y `Cookie` con valores marcados y comprueba que no salen |
| El cuerpo no llega a los logs ni a los errores | Textos de error fijos | `LogsSinIdentidadTest` y `ManejadorErroresTest`, con JSON malformado y excepciones con valores marcados |
| Las peticiones rechazadas por Tomcat no se registran | Niveles de log de Tomcat en `ERROR` | `LogsSinIdentidadTest` envía una petición malformada por socket |
| No hay registro de accesos ni se interpretan headers de proxy | `server.tomcat.accesslog.enabled=false` y `server.forward-headers-strategy=none` | `LogsSinIdentidadTest` comprueba que Tomcat no tenga esas válvulas |
| Actuator no expone datos de peticiones | Solo se expone `health` | `ManejadorErroresTest` comprueba `404` en `/actuator/env` y `/actuator/httpexchanges` |
