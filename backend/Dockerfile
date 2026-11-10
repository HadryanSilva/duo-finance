# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Dependencies first so this layer is cached until pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
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
