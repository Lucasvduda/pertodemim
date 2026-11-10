# API PertoDeMim (Spring Boot 3.5, Java 17). Build em dois estágios; execução sem root.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Os testes rodam no CI (mvn verify); aqui só se empacota.
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17.0.13_11-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home app \
    && mkdir -p /data/bronze && chown -R app:app /data
WORKDIR /app
COPY --from=build /build/target/pertodemim-api-1.0.0.jar app.jar
USER 10001
ENV BRONZE_DIR=/data/bronze \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
