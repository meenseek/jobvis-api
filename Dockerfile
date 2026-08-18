FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre-jammy

RUN groupadd --system jobvis && useradd --system --gid jobvis --home-dir /app jobvis
WORKDIR /app
COPY --from=build --chown=jobvis:jobvis /workspace/build/libs/jobvis-api-0.0.1-SNAPSHOT.jar /app/jobvis-api.jar

USER jobvis
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/jobvis-api.jar"]
