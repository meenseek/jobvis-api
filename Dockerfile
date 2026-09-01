FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system jobvis \
    && useradd --system --gid jobvis --home-dir /app jobvis
WORKDIR /app
COPY --from=build --chown=jobvis:jobvis /workspace/build/libs/jobvis-api.jar /app/jobvis-api.jar

USER jobvis
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:InitialRAMPercentage=10.0", "-XX:MaxRAMPercentage=65.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/jobvis-api.jar"]
