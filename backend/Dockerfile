# syntax=docker/dockerfile:1
# Maven ships in the build image instead of being downloaded by mvnw: Maven Central answers the
# distribution download from some CI runners with HTTP 429. Keep the tag's Maven version equal to
# .mvn/wrapper/maven-wrapper.properties (checked in infra/tests/config.bats).
FROM maven:3.10.0-eclipse-temurin-25 AS build
WORKDIR /src

# Dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src/ src/
RUN mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app

# Least to most frequently changing, so rebuilds reuse the big dependency layer.
COPY --from=build --chown=app:app /layers/dependencies/ ./
COPY --from=build --chown=app:app /layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /layers/application/ ./

USER 10001:10001
EXPOSE 8080
# No HEALTHCHECK here: the JRE image has neither wget nor curl and we add no packages.
# The healthcheck is defined in docker-compose (/api/actuator/health).
# Exec form: Java is PID 1 and receives SIGTERM for graceful shutdown.
ENTRYPOINT ["java","-XX:MaxRAMPercentage=60","-XX:+ExitOnOutOfMemoryError","org.springframework.boot.loader.launch.JarLauncher"]
