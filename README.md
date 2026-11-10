# Duo Finance

Aplicativo web de finanças para casais. Backend Spring Boot (Java 25), frontend Vue 3 + Vite,
PostgreSQL 16; em produção numa instância Lightsail com Docker Compose, nginx e Let's Encrypt.

## Documentação

- [Spec do MVP](docs/superpowers/specs/2026-11-05-duo-finance-mvp-design.md)
- [Planos de implementação](docs/superpowers/plans)
- [Runbook de provisionamento e operação](docs/runbook.md)

## Desenvolvimento local

Requisitos: Docker, Java 25 e Node 24.

```bash
# 1. Postgres 16 de desenvolvimento
docker compose -f infra/docker-compose.dev.yml up -d

# 2. Backend (perfil dev: os e-mails são impressos no log)
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Frontend, em outro terminal (proxy de /api para localhost:8080)
cd frontend
npm ci
npm run dev
```

## Testes

```bash
# Backend (Testcontainers, precisa do Docker)
cd backend && ./mvnw verify

# Frontend
cd frontend && npm test

# E2E (o Playwright sobe backend e Vite)
cd frontend && npm run e2e:install && npm run e2e

# Infra (precisa de shellcheck e bats; os testes "docker" precisam do Docker)
shellcheck infra/scripts/*.sh
bats --filter-tags '!docker' infra/tests/*.bats
bats --filter-tags docker infra/tests/*.bats
```
