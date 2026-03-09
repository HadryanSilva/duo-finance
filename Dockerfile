# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build JAR com Microsoft OpenJDK 25
# ─────────────────────────────────────────────────────────────────────────────
FROM mcr.microsoft.com/openjdk/jdk:25-ubuntu-24.04 AS builder

WORKDIR /app

COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x gradlew

# Baixa dependências (cache layer separado)
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

COPY src/ src/

RUN ./gradlew bootJar --no-daemon -x test

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Imagem final
# ─────────────────────────────────────────────────────────────────────────────
FROM mcr.microsoft.com/openjdk/jdk:25-ubuntu-24.04

WORKDIR /app

RUN useradd -r -s /bin/false appuser

COPY --from=builder /app/build/libs/*.jar app.jar

RUN chown appuser:appuser app.jar
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]