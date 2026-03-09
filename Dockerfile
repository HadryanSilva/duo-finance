# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build nativo com GraalVM
# ─────────────────────────────────────────────────────────────────────────────
FROM ghcr.io/graalvm/native-image-community:24 AS builder

WORKDIR /app

# Copia wrapper e arquivos de configuração do Gradle primeiro (melhor cache)
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle .
COPY settings.gradle .

# Garante que o wrapper seja executável
RUN chmod +x gradlew

# Baixa dependências sem compilar (cache layer)
RUN ./gradlew dependencies --no-daemon -q

# Copia o código-fonte
COPY src/ src/

# Compila o binário nativo
# -Pnative ativa o plugin GraalVM Native Build Tools
RUN ./gradlew nativeCompile --no-daemon -Pnative \
    -x test \
    --info

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Imagem final mínima (sem JVM)
# ─────────────────────────────────────────────────────────────────────────────
FROM debian:bookworm-slim

WORKDIR /app

# Dependências mínimas para o binário nativo em runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copia apenas o binário compilado do stage anterior
COPY --from=builder /app/build/native/nativeCompile/duo-finance .

# Usuário não-root por segurança
RUN useradd -r -s /bin/false appuser && chown appuser:appuser duo-finance
USER appuser

EXPOSE 8080

# Ativa o profile de produção e aponta para o binário
ENTRYPOINT ["./duo-finance", \
    "--spring.profiles.active=prod"]