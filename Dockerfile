# syntax=docker/dockerfile:1
# Imagen de servired-moderacion (Spring Boot). Puerto 8082, healthcheck en /actuator/health.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN sh ./mvnw -B -ntp -q dependency:go-offline
COPY src/ src/
RUN sh ./mvnw -B -ntp -q package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
# curl solo para el HEALTHCHECK (la imagen base no trae herramientas HTTP).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --no-create-home --shell /usr/sbin/nologin moderacion
WORKDIR /app
COPY --from=build /src/target/servired-moderacion.jar app.jar
EXPOSE 8082
USER moderacion
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:8082/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
