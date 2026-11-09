# Infraestrutura e Deploy — Implementation Plan (4 de 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deixar o Duo Finance pronto para produção numa instância AWS Lightsail com Docker Compose. Isso inclui:
- perfil `prod` do backend;
- imagens `api` e `nginx`;
- compose de produção com TLS;
- CI de pull request e CD na `main` (GHCR + SSH, com volta à tag anterior);
- backup diário;
- runbook de provisionamento que o responsável executa uma única vez.

**Architecture:** Tudo que pode ser testado localmente fica no repositório e é testado localmente ou no CI:
- Dockerfiles, compose, configuração do nginx, scripts e workflows;
- um smoke test sobe a pilha inteira (HTTP, sem TLS) e a exercita.

O que exige conta na AWS, DNS ou segredos fica no `docs/runbook.md`, como passos explícitos para uma pessoa executar: criar a instância, IP estático, firewall, DNS, `.env` com segredos, primeira emissão do certificado, segredos do GitHub e bucket de backup. Nenhuma task deste plano age em contas externas.

**Tech Stack:** Docker (multi-stage), Docker Compose v2, nginx (stable-alpine), certbot, eclipse-temurin 25 (JRE), Node 24, GitHub Actions, GHCR, Lightsail Object Storage (API S3, via `aws` CLI), bash + shellcheck, bats-core para testes de script.

**Spec:** `docs/superpowers/specs/2026-11-05-duo-finance-mvp-design.md` (seção 5 — Infraestrutura e deploy; seção 3 — Segurança).

**Sequência de planos:** (1) fundação e identidade; (2) domínio financeiro; (3) frontend — PR #3; (4) este. Este branch (`feature/infra`) parte de `feature/frontend`.

## Global Constraints

- **Instância:** Lightsail Ubuntu LTS com 2 GB de RAM, Docker Compose com os serviços `nginx`, `api`, `postgres` (16) e `certbot`.
  - `api` e `postgres` ficam só na rede interna do compose; apenas o `nginx` publica as portas 80 e 443.
  - Firewall: 22 (restrita ao IP do administrador), 80 e 443.
- **nginx:**
  - imagem própria contendo o build do Vue, com fallback SPA para `index.html` e proxy de `/api/` para `api:8080`;
  - TLS Let's Encrypt (certbot, renovação automática) para `duofinance.dev.br`, com redirect de `www` e de HTTP para HTTPS;
  - `limit_req` em `/api/v1/auth/` e `/api/v1/public/`;
  - headers HSTS, CSP, `X-Content-Type-Options` e `Referrer-Policy`.
- **Segredos:** senha do banco, chave privada RSA do JWT e API key do Resend ficam num `.env` na instância, fora do git. O repositório só tem `infra/.env.example`.
- **CI no pull request:** testes do backend (Testcontainers) e do frontend, lint e type-check.
- **CD no merge na `main`:**
  1. build das imagens `api` e `nginx` com tag do commit (`sha` curto de 12 caracteres) e push para o GHCR (`ghcr.io/hadryansilva/duo-finance-api` e `…-nginx`);
  2. deploy via SSH (`docker compose pull && docker compose up -d`);
  3. health check em `/api/actuator/health`;
  4. em caso de falha, volta para a tag anterior.
- **Migrations:** o Flyway aplica as migrations no startup do backend.
- **Backup e observabilidade:**
  - `pg_dump` diário compactado, enviado ao Lightsail Object Storage com retenção de 30 dias;
  - snapshot automático semanal da instância (configurado no runbook);
  - logs em JSON no stdout, com rotação do driver do Docker;
  - o actuator expõe apenas `health`.
- **Preparado para migrar** o banco para o Lightsail Managed Database trocando apenas o datasource (variáveis `DB_URL`, `DB_USER`, `DB_PASSWORD`).
- **Backend em produção:**
  - roda com o perfil `prod`;
  - falha no startup se `e2e` estiver ativo junto com `prod`;
  - sem perfil `dev`/`test`, exige `app.public-base-url` e o par de chaves JWT (já implementado no plano 1).
- **Frontend:** build com `npm ci && npm run build` (Node 24), saída em `frontend/dist`, base `/`, sem variáveis `VITE_*`. A API é same-origin `/api/v1`.
- **Scripts:** `bash` com `set -euo pipefail`, aprovados pelo `shellcheck`, com testes em `infra/tests/*.bats` rodados com `bats`.
- **Commits:** terminam com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Deploy com imagem que não sobe** (migration falha, variável ausente): o health check falha em até 120 s, o script volta para a tag anterior e sai com código ≠ 0. O site continua no ar com a versão anterior. Teste em Task 6 (`deploy_rollsBackWhenHealthFails`).
2. **Rotas do Vue acessadas direto ou recarregadas** (`/transactions?month=…`, `/invite/<token>`): o nginx devolve `index.html` (200), nunca 404. Asset inexistente em `/assets/` responde 404, e não o `index.html`. Teste em Task 3 (`spa_deepLinkServesIndex`, `missingAsset_is404`).
3. **Rajada de tentativas de login**: o `limit_req` responde `429` sem derrubar o restante do site. O `X-Forwarded-For`/`X-Real-IP` chega ao backend e o IP real é o considerado. Teste em Task 3 (`authBurst_isRateLimited`) e Task 1 (`forwardedHeaders_areHonoured`).
4. **`index.html` em cache após um deploy**: o navegador não fica preso numa versão antiga que pede assets já removidos. `index.html` sai com `Cache-Control: no-cache`; `/assets/*` com hash sai com `max-age=31536000, immutable`. Teste em Task 3 (`cacheHeaders`).
5. **Backup que "funciona" mas não restaura**: o dump é restaurável num Postgres vazio, e o script falha (código ≠ 0) se o `pg_dump` ou o upload falhar, sem deixar arquivo parcial. Teste em Task 7 (`backup_restoresIntoEmptyDatabase`, `backup_failsLoudly`).

---

## File Structure

```
.github/workflows/ci.yml, deploy.yml
backend/Dockerfile, backend/.dockerignore
backend/src/main/resources/application-prod.yml
backend/src/main/java/br/dev/duofinance/shared/config/ProfileGuard.java
frontend/.nvmrc, frontend/Dockerfile (nginx), frontend/.dockerignore
infra/
├── docker-compose.yml            (produção: nginx, api, postgres, certbot)
├── docker-compose.local.yml      (override para smoke local: HTTP, imagens locais, sem certbot)
├── .env.example
├── nginx/
│   ├── nginx.conf                (http, gzip, limit_req zones, log json)
│   ├── templates/app.conf.template      (produção: 80→443, www→apex, TLS, SPA, /api, headers)
│   ├── templates/app-local.conf.template (local: só HTTP, mesmas regras de app/SPA/headers exceto HSTS)
│   └── snippets/app-common.conf   (SPA, cache, proxy /api, headers comuns — incluído pelos dois templates)
├── scripts/deploy.sh, backup.sh, restore.sh, smoke.sh, gen-jwt-keys.sh, install-cron.sh
└── tests/deploy.bats, backup.bats, nginx.bats, helpers/ (stubs de docker/aws/curl)
docs/runbook.md
```

---

### Task 1: Backend pronto para produção

**Files:**
- Create: `backend/src/main/resources/application-prod.yml`; `shared/config/ProfileGuard.java`
- Modify: `shared/email/ResendEmailSender.java` (timeouts), `application.yml` (shutdown gracioso, forward headers)
- Test: `shared/config/ProfileGuardTest.java`, `shared/config/ProdProfileTest.java`, `shared/email/ResendEmailSenderTest.java` (caso novo), `shared/web/ForwardedHeadersTest.java`

**Interfaces:**
- Produces:
  - **`application-prod.yml`:**
    - **Datasource:** `spring.datasource.url/username/password` vêm de `${DB_URL}`, `${DB_USER}` e `${DB_PASSWORD}`.
    - **App:**
      - `app.public-base-url: ${PUBLIC_BASE_URL}`;
      - `app.auth.jwt-private-key: ${JWT_PRIVATE_KEY}` e `jwt-public-key: ${JWT_PUBLIC_KEY}` (PEM em uma linha com `\n`, ou caminho; use o formato que o `JwtKeyConfig` já aceita e documente);
      - `app.auth.cookie-secure: true`;
      - `app.email.provider: RESEND`, `resend-api-key: ${RESEND_API_KEY}`, `from: ${EMAIL_FROM}`.
    - **Logs:** `logging.structured.format.console: ecs` (JSON no stdout).
    - **Rede:** `server.forward-headers-strategy: native`, com os headers do nginx confiáveis.
    - **Hikari:** `maximum-pool-size: 10`, adequado a 2 GB.
  - **`application.yml`:** `server.shutdown: graceful` e `spring.lifecycle.timeout-per-shutdown-phase: 20s`.
  - **`ProfileGuard`:** um `EnvironmentPostProcessor` (registrado em `META-INF/spring/…EnvironmentPostProcessor` / `spring.factories`, conforme a versão do Boot 4) que lança `IllegalStateException("profile e2e must never run with prod")` quando `prod` e `e2e` estão ativos ao mesmo tempo.
  - **`ResendEmailSender`:** timeouts de conexão (5 s) e leitura (10 s) no `RestClient`, via `ClientHttpRequestFactorySettings`/`JdkClientHttpRequestFactory`.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void prodAndE2eTogether_failsAtStartup() { /* SpringApplicationBuilder com profiles prod,e2e (datasource Testcontainers) → falha com a mensagem acima */ }
@Test void prodProfile_logsJsonAndTrustsForwardedHeaders() { /* contexto prod com envs mínimos: propriedade structured format = ecs; forward-headers-strategy = native */ }
@Test void forwardedHeaders_areHonoured() { /* MockMvc com X-Forwarded-Proto: https, X-Forwarded-For: 203.0.113.9 → request.isSecure() e remoteAddr = 203.0.113.9 (endpoint de teste em __test) */ }
@Test void resend_timesOutOnSlowServer() { /* servidor que não responde → exceção em ≤ 11 s, não pendura */ }
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest='ProfileGuardTest,ProdProfileTest,ForwardedHeadersTest,ResendEmailSenderTest'` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — o mesmo comando → PASS; depois `./mvnw test` completo.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): production profile, e2e/prod guard, forwarded headers and Resend timeouts"
```

---

### Task 2: Imagem Docker do backend

**Files:**
- Create: `backend/Dockerfile`, `backend/.dockerignore`
- Test: `infra/tests/images.bats` (casos do backend)

**Interfaces:**
- Produces:
  - **`backend/Dockerfile` multi-stage:**
    - estágio `build` (`eclipse-temurin:25-jdk`): `./mvnw -B -q dependency:go-offline` com cache de dependências, depois `./mvnw -B -q package -DskipTests` e `java -Djarmode=tools -jar … extract --layers --launcher` (camadas do Boot);
    - estágio final (`eclipse-temurin:25-jre`): usuário não-root `app` (uid 10001), `WORKDIR /app`, camadas copiadas em ordem (dependências primeiro), `EXPOSE 8080`, `ENTRYPOINT ["java","-XX:MaxRAMPercentage=60","-XX:+ExitOnOutOfMemoryError","org.springframework.boot.loader.launch.JarLauncher"]`;
    - `HEALTHCHECK` usando `wget`/`curl` disponível na imagem, ou um healthcheck no compose (sem pacote extra na imagem).
  - **`.dockerignore`:** exclui `target/`, `.mvn/wrapper/maven-wrapper.jar` se não for necessário, e arquivos de IDE.

- [ ] **Step 1: Escrever os testes**

```bash
# images.bats
@test "api image builds, runs as non-root and answers health with prod profile" {
  # docker build -t duo-api:test backend/
  # docker run com rede + postgres:16 efêmero, SPRING_PROFILES_ACTIVE=prod e envs de teste (chaves geradas por gen-jwt-keys.sh)
  # espera até 90 s: GET http://localhost:<porta>/api/actuator/health → {"status":"UP"}
  # docker exec … id -u → 10001
}
@test "api image refuses prod+e2e" { # SPRING_PROFILES_ACTIVE=prod,e2e → container sai com código ≠ 0 e log contém "must never run with prod" }
```

- [ ] **Step 2: Rodar e ver falhar** — `bats infra/tests/images.bats` → FAIL (sem Dockerfile).
- [ ] **Step 3: Implementar** o Dockerfile e o script `infra/scripts/gen-jwt-keys.sh`, que gera o par RSA 2048 com `openssl` no formato aceito pelo backend e imprime as linhas `JWT_PRIVATE_KEY=…` e `JWT_PUBLIC_KEY=…`.
- [ ] **Step 4: Rodar e ver passar** — `bats infra/tests/images.bats` → PASS; `shellcheck infra/scripts/*.sh` → sem avisos.
- [ ] **Step 5: Commit**

```bash
git add backend/Dockerfile backend/.dockerignore infra/scripts/gen-jwt-keys.sh infra/tests
git commit -m "build(backend): layered non-root runtime image"
```

---

### Task 3: Imagem nginx com o frontend

**Files:**
- Create: `frontend/Dockerfile`, `frontend/.dockerignore`, `frontend/.nvmrc` (`24`); `infra/nginx/nginx.conf`, `infra/nginx/snippets/app-common.conf`, `infra/nginx/templates/app.conf.template`, `infra/nginx/templates/app-local.conf.template`
- Modify: `frontend/package.json` (`"engines": { "node": ">=24 <25" }`)
- Test: `infra/tests/nginx.bats`

**Interfaces:**
- Produces:
  - **`frontend/Dockerfile`:**
    - Build context na raiz do repositório (`docker build -f frontend/Dockerfile .`) para alcançar `infra/nginx`.
    - Estágio `build` (`node:24-alpine`): `npm ci` e `npm run build`.
    - Estágio final (`nginx:stable-alpine`): copia `dist/` para `/usr/share/nginx/html`, `infra/nginx/nginx.conf` para `/etc/nginx/nginx.conf`, `snippets/` para `/etc/nginx/snippets/` e `templates/` para `/etc/nginx/templates/`. A imagem oficial já faz `envsubst` dos templates no start.
    - Variáveis: `NGINX_SERVER_NAME` (padrão `duofinance.dev.br`) e `NGINX_TEMPLATE` (`app` ou `app-local`). Use um entrypoint mínimo que remova o template que não for o escolhido antes do entrypoint oficial.
  - **`nginx.conf`:**
    - `server_tokens off`, gzip para texto/JS/CSS/JSON/SVG, `client_max_body_size 1m`;
    - log em JSON (`log_format json escape=json …`) no stdout;
    - zonas `limit_req_zone $binary_remote_addr zone=auth:10m rate=10r/m` e `zone=public:10m rate=30r/m`;
    - `real_ip` não necessário: o nginx é a borda.
  - **`snippets/app-common.conf`:**
    - `root /usr/share/nginx/html;`
    - `location = /index.html { add_header Cache-Control "no-cache"; … }`
    - `location /assets/ { add_header Cache-Control "public, max-age=31536000, immutable"; try_files $uri =404; }`
    - `location /api/v1/auth/ { limit_req zone=auth burst=5 nodelay; limit_req_status 429; proxy_pass http://api:8080; … }`
    - `location /api/v1/public/ { limit_req zone=public burst=10 nodelay; limit_req_status 429; proxy_pass http://api:8080; … }`
    - `location /api/ { proxy_pass http://api:8080; proxy_set_header Host $host; X-Real-IP $remote_addr; X-Forwarded-For $proxy_add_x_forwarded_for; X-Forwarded-Proto $scheme; }`
    - `location /api/v1/__e2e/ { return 404; }` como defesa extra.
    - `location / { try_files $uri $uri/ /index.html; }`
    - headers em todas as respostas (`always`):
      - `X-Content-Type-Options nosniff`, `Referrer-Policy strict-origin-when-cross-origin`;
      - `Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"`;
      - o `'unsafe-inline'` em `style-src` é necessário para os `style` dinâmicos do Vue e do ECharts. Confirme no app servido e documente se puder ser removido.
  - **`app.conf.template` (produção):**
    - `server` 80 com `location /.well-known/acme-challenge/ { root /var/www/certbot; }` e `return 301 https://${NGINX_SERVER_NAME}$request_uri` para o resto;
    - `server` 443 para `www.${NGINX_SERVER_NAME}` com `return 301 https://${NGINX_SERVER_NAME}$request_uri`;
    - `server` 443 `ssl http2` para `${NGINX_SERVER_NAME}`, com certificados em `/etc/letsencrypt/live/${NGINX_SERVER_NAME}/`, TLS 1.2/1.3, `add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;` e `include snippets/app-common.conf`;
    - **Primeira subida, antes do certificado existir:** o runbook usa um certificado autoassinado temporário gerado pelo certbot (procedimento padrão `--staging`/dummy). O template não precisa de condicionais.
  - **`app-local.conf.template`:** `server` 80 com `include snippets/app-common.conf`, sem HSTS e sem TLS.

- [ ] **Step 1: Escrever os testes**

```bash
# nginx.bats — sobe infra/docker-compose.yml + docker-compose.local.yml (Task 4 entrega o compose; nesta task use um compose mínimo de teste em infra/tests/fixtures/ com nginx + um "api" stub (imagem nginx que responde JSON em /api/actuator/health e /api/v1/auth/login))
@test "nginx config is valid" { # docker run --rm <img> nginx -t }
@test "spa_deepLinkServesIndex" { # GET /transactions?month=2026-11 → 200, corpo contém <div id="app"> }
@test "missingAsset_is404" { # GET /assets/nao-existe.js → 404 }
@test "cacheHeaders" { # / → Cache-Control no-cache; /assets/<arquivo real do build> → max-age=31536000, immutable }
@test "securityHeaders" { # CSP, X-Content-Type-Options, Referrer-Policy presentes; HSTS ausente no local }
@test "apiProxy_forwardsHeaders" { # stub ecoa X-Forwarded-For/Proto → presentes }
@test "authBurst_isRateLimited" { # 20 POSTs seguidos em /api/v1/auth/login → pelo menos um 429; GET / continua 200 }
@test "e2eOutbox_isBlocked" { # GET /api/v1/__e2e/outbox → 404 vindo do nginx }
@test "prod template renders and validates with a dummy cert" { # NGINX_TEMPLATE=app + certificado autoassinado montado → nginx -t ok }
```

- [ ] **Step 2: Rodar e ver falhar** — `bats infra/tests/nginx.bats` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — `bats infra/tests/nginx.bats` → PASS.
- [ ] **Step 5: Commit**

```bash
git add frontend/Dockerfile frontend/.dockerignore frontend/.nvmrc frontend/package.json infra/nginx infra/tests
git commit -m "build(infra): nginx image with SPA, API proxy, caching, security headers and rate limits"
```

---

### Task 4: Compose de produção e smoke local da pilha inteira

**Files:**
- Create: `infra/docker-compose.yml`, `infra/docker-compose.local.yml`, `infra/.env.example`, `infra/scripts/smoke.sh`
- Modify: `infra/docker-compose.dev.yml` (sem mudança funcional; só comentário apontando os outros arquivos)
- Test: `infra/tests/stack.bats`

**Interfaces:**
- Produces:
  - **`docker-compose.yml` (produção):**
    - **Serviços:**
      - `nginx` (`ghcr.io/hadryansilva/duo-finance-nginx:${IMAGE_TAG}`): portas `80:80` e `443:443`, volumes `certbot-etc:/etc/letsencrypt:ro` e `certbot-www:/var/www/certbot:ro`, `depends_on: api (service_healthy)`;
      - `api` (`…-api:${IMAGE_TAG}`): `env_file: .env`, `SPRING_PROFILES_ACTIVE=prod`, sem `ports`, healthcheck em `http://localhost:8080/api/actuator/health`, `depends_on: postgres (service_healthy)`, `mem_limit: 900m`;
      - `postgres` (`postgres:16`): volume `pgdata`, `POSTGRES_*` do `.env`, healthcheck `pg_isready`, sem `ports`;
      - `certbot` (`certbot/certbot`): `entrypoint` em loop `certbot renew --webroot -w /var/www/certbot --quiet; sleep 12h`, com os volumes `certbot-etc` e `certbot-www`.
    - **Opções comuns:** `restart: unless-stopped`; logging `driver: json-file`, `max-size: 10m`, `max-file: "5"`.
    - **Rede:** a rede padrão é interna para `api` e `postgres`.
    - **Recarga do certificado:** o `nginx` recarrega a cada 6 h (`nginx -s reload` num loop leve no entrypoint) para pegar certificados renovados.
  - **`docker-compose.local.yml` (override):**
    - imagens locais (`duo-api:local`, `duo-nginx:local`) com `build:` apontando para os Dockerfiles;
    - `NGINX_TEMPLATE=app-local`, porta `8088:80`, sem 443 e sem certbot (`profiles: [never]`);
    - `env_file: .env.local.example`, versionado, apenas valores de teste.
  - **`.env.example`:** todas as variáveis com comentários: `IMAGE_TAG`, `POSTGRES_DB/USER/PASSWORD`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `PUBLIC_BASE_URL`, `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `RESEND_API_KEY`, `EMAIL_FROM`, `BACKUP_S3_BUCKET`, `BACKUP_S3_ENDPOINT`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`.
  - **`smoke.sh <base-url>`:** checa
    - `/api/actuator/health` UP;
    - `/` 200 com `<div id="app">`;
    - `/api/v1/public/registration-mode` 200;
    - `/transactions` 200, via fallback SPA.

    Sai ≠ 0 na primeira falha, com mensagem. Usado pelo deploy (Task 6) e pelo CI (Task 5).

- [ ] **Step 1: Escrever os testes**

```bash
# stack.bats
@test "local stack boots and passes smoke" { # docker compose -f infra/docker-compose.yml -f infra/docker-compose.local.yml up -d --build --wait; infra/scripts/smoke.sh http://localhost:8088 → 0 }
@test "api and postgres are not published" { # docker compose port api 8080 → vazio; idem postgres 5432 }
@test "flyway migrated on startup" { # psql no container: SELECT max(version) FROM flyway_schema_history → '4' }
@test "public api works through nginx" { # GET /api/v1/public/registration-mode → {"mode":"INVITE_ONLY"}; POST /api/v1/public/waitlist {email, locale} → 202 }
```

- [ ] **Step 2: Rodar e ver falhar** — `bats infra/tests/stack.bats` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — `bats infra/tests/stack.bats` → PASS; depois `docker compose … down -v`.
- [ ] **Step 5: Commit**

```bash
git add infra
git commit -m "feat(infra): production compose, local stack override and smoke script"
```

---

### Task 5: CI de pull request

**Files:**
- Create: `.github/workflows/ci.yml`
- Test: validação com `actionlint` (rodado localmente via container `rhysd/actionlint`) e a primeira execução no próprio PR

**Interfaces:**
- Produces — o workflow `CI`, disparado em `pull_request` e `push` para `main`, com `concurrency` por ref e cancel-in-progress. Jobs:
  1. **`backend`** (`ubuntu-latest`): `actions/setup-java` (temurin 25, cache maven), `./mvnw -B verify`. Testcontainers usa o Docker do runner.
  2. **`frontend`:** `actions/setup-node` (`node-version-file: frontend/.nvmrc`, cache npm), depois `npm ci`, `npm run lint`, `npm run test` e `npm run build`.
  3. **`api-types`** (depende de `backend`):
     - serviço `postgres:16`, com as credenciais do `application-dev.yml`;
     - sobe o backend (`./mvnw -B -q spring-boot:run -Dspring-boot.run.profiles=dev,e2e &`) e espera o health;
     - roda `npm ci && npm run api:types` e `git diff --exit-code frontend/src/shared/api/schema.ts`. A mensagem de falha deve ser "schema.ts out of date — run npm run api:types".
  4. **`e2e`** (depende de `frontend` e `backend`):
     - serviço `postgres:16`;
     - `npx playwright install --with-deps chromium` e `npm run e2e` com `CI=true`;
     - em falha, faz upload de `playwright-report/` como artefato.
  5. **`infra`:**
     - `shellcheck infra/scripts/*.sh`;
     - `bats infra/tests/deploy.bats infra/tests/backup.bats`, os que não precisam de Docker pesado;
     - `bats infra/tests/nginx.bats infra/tests/stack.bats`, com Docker do runner;
     - `actionlint`.
- **Contrato:** os jobs têm nomes estáveis (`backend`, `frontend`, `api-types`, `e2e`, `infra`), para serem marcados como obrigatórios na proteção da `main` (runbook).

- [ ] **Step 1: Escrever o workflow e validar** — `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest` → sem erros.
- [ ] **Step 2: Rodar localmente o que for possível** — os comandos de cada job, na sequência, a partir da raiz; todos com código 0.
- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: pull request checks for backend, frontend, api types, e2e and infra"
```

A execução real acontece quando o branch for publicado num PR. O controller verifica o resultado depois do push, autorizado pelo usuário.

---

### Task 6: CD — imagens no GHCR e deploy com volta automática

**Files:**
- Create: `.github/workflows/deploy.yml`, `infra/scripts/deploy.sh`
- Test: `infra/tests/deploy.bats` (com stubs de `docker` e `curl` em `infra/tests/helpers/`)

**Interfaces:**
- Produces:
  - **`deploy.sh <new-tag>`** (roda na instância, em `/opt/duo-finance`):
    1. Lê a tag atual de `IMAGE_TAG` no `.env`, grava em `.previous-tag` e escreve a nova tag no `.env`.
    2. Roda `docker compose pull api nginx` e `docker compose up -d --remove-orphans`.
    3. Espera até 120 s por `smoke.sh https://$DOMAIN`. O domínio vem de `PUBLIC_BASE_URL`, e `SMOKE_BASE_URL` sobrescreve para teste.
    4. Se falhar: restaura a tag anterior no `.env`, roda `docker compose up -d` e de novo o smoke, e sai com 1 e a mensagem "rolled back to <tag>". Se o rollback também falhar, sai com 2.
    5. Em sucesso, remove imagens antigas da mesma repo, mantendo a atual e a anterior (`docker image prune` filtrado).
    6. Usa lock (`flock /tmp/duo-deploy.lock`) para não haver dois deploys simultâneos. Cada passo vai para o log com timestamp.
  - **`deploy.yml`:**
    - Disparado em `push` na `main`, depois do `CI` verde (`workflow_run` em `CI` com `conclusion == success`, ou os mesmos jobs como `needs`). Escolha `workflow_run` e documente.
    - **Job `images`:** `docker/login-action` (GHCR com `GITHUB_TOKEN`, `permissions: packages: write`), `docker/build-push-action` para `backend/Dockerfile` e `frontend/Dockerfile` (contexto na raiz). Tags: `sha-<12>` e `main`. Cache `type=gha`.
    - **Job `deploy`** (depende de `images`, `environment: production`):
      - SSH com `webfactory/ssh-agent` (segredo `DEPLOY_SSH_KEY`) e `known_hosts` de `DEPLOY_KNOWN_HOSTS` (nunca `StrictHostKeyChecking=no`);
      - `scp infra/docker-compose.yml infra/scripts/*.sh` para `${DEPLOY_PATH}`;
      - `ssh ${DEPLOY_USER}@${DEPLOY_HOST} "cd ${DEPLOY_PATH} && ./deploy.sh sha-<12>"`;
      - o código de saída do job é o do script.
  - **Segredos e variáveis do GitHub** (documentados no runbook): `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`, `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_PATH`. A instância faz `docker login ghcr.io` uma vez com um token de leitura de pacotes, conforme o runbook.

- [ ] **Step 1: Escrever os testes que falham**

```bash
# deploy.bats — PATH com stubs que registram chamadas; .env temporário com IMAGE_TAG=sha-old
@test "deploy_updatesTagAndPassesSmoke" { # smoke stub ok → .env com sha-new, .previous-tag = sha-old, saída 0 }
@test "deploy_rollsBackWhenHealthFails" { # smoke stub falha para sha-new e passa para sha-old → .env volta para sha-old, saída 1, log "rolled back to sha-old" }
@test "deploy_exits2WhenRollbackAlsoFails" {}
@test "deploy_refusesConcurrentRun" { # lock já tomado → sai ≠ 0 sem tocar no .env }
@test "deploy_requiresTagArgument" {}
```

- [ ] **Step 2: Rodar e ver falhar** — `bats infra/tests/deploy.bats` → FAIL.
- [ ] **Step 3: Implementar** o script e o workflow; validar o workflow com `actionlint`.
- [ ] **Step 4: Rodar e ver passar** — `bats infra/tests/deploy.bats` → PASS; `shellcheck` e `actionlint` limpos.
- [ ] **Step 5: Commit**

```bash
git add .github/workflows/deploy.yml infra/scripts/deploy.sh infra/tests
git commit -m "cd: publish images to GHCR and deploy over SSH with automatic rollback"
```

---

### Task 7: Backup diário e restauração

**Files:**
- Create: `infra/scripts/backup.sh`, `infra/scripts/restore.sh`, `infra/scripts/install-cron.sh`
- Test: `infra/tests/backup.bats`

**Interfaces:**
- Produces:
  - **`backup.sh`** (roda na instância via cron):
    1. `docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"` para um arquivo temporário, `duofinance-<UTC YYYYMMDD-HHMMSS>.dump` (o formato custom já é compactado), e valida com `pg_restore --list`.
    2. Envia com `aws s3 cp` para `s3://$BACKUP_S3_BUCKET/daily/`, com `--endpoint-url "$BACKUP_S3_ENDPOINT"` quando definido.
    3. Remove o temporário (`trap` em qualquer saída).
    4. Apaga do bucket os objetos de `daily/` com mais de 30 dias (`aws s3api list-objects-v2` com filtro de data). O runbook também configura a regra de ciclo de vida do bucket como redundância.
    5. Sai ≠ 0 em qualquer falha (`set -euo pipefail`), sem deixar arquivo parcial no bucket: envia para `…/.partial` e renomeia só no fim.
    6. `BACKUP_TARGET=file:///caminho` troca o S3 por diretório local, para testes e restauração manual.
  - **`restore.sh <arquivo-ou-s3-uri>`:** baixa o dump, se for S3, e roda `pg_restore --clean --if-exists --no-owner` no `postgres` do compose. Exige `--yes` para executar e imprime antes o que vai fazer.
  - **`install-cron.sh`:** instala em `/etc/cron.d/duo-finance-backup` a linha `30 3 * * * <usuário> cd /opt/duo-finance && ./backup.sh >> /var/log/duo-finance-backup.log 2>&1`, de forma idempotente.

- [ ] **Step 1: Escrever os testes que falham**

```bash
# backup.bats — usa o postgres do compose local (Task 4) e BACKUP_TARGET=file://$BATS_TEST_TMPDIR/bk
@test "backup_writesValidDump" { # arquivo .dump existe e pg_restore --list lista tabelas users, transactions }
@test "backup_restoresIntoEmptyDatabase" { # cria dados (via API ou psql), backup, apaga DB, restore.sh --yes → contagens iguais }
@test "backup_failsLoudly" { # POSTGRES_USER errado → saída ≠ 0 e nenhum arquivo final no destino }
@test "backup_prunesOlderThan30Days" { # destino com arquivos datados (touch -d) de 31 e 29 dias → só o de 31 some }
@test "installCron_isIdempotent" { # rodar 2x com CRON_DIR temporário → uma única linha }
```

- [ ] **Step 2: Rodar e ver falhar** — `bats infra/tests/backup.bats` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — `bats infra/tests/backup.bats` → PASS; `shellcheck` limpo.
- [ ] **Step 5: Commit**

```bash
git add infra/scripts infra/tests
git commit -m "feat(infra): daily compressed Postgres backup to object storage with 30-day retention and restore"
```

---

### Task 8: Runbook de provisionamento e operação

**Files:**
- Create: `docs/runbook.md`
- Modify: `README.md` (se existir; senão crie um curto, com links para spec, planos, runbook e comandos de dev)
- Test: revisão manual contra a checklist abaixo e `markdownlint` (via `npx markdownlint-cli2`)

**Interfaces:**
- Produces — o `runbook.md` com seções numeradas, cada passo com o comando exato e o resultado esperado:
  1. **Pré-requisitos:** conta AWS, domínio `duofinance.dev.br`, conta Resend com o domínio verificado (SPF/DKIM), repositório GitHub.
  2. **Instância:**
     - criação no Lightsail (Ubuntu LTS, plano de 2 GB, região sugerida) e IP estático;
     - firewall: 22 só do IP do administrador, 80 e 443;
     - snapshots automáticos do Lightsail ligados. O Lightsail só oferece snapshots diários (guarda os últimos 7), o que cobre e supera o "snapshot automático semanal" da spec;
     - swap de 2 GB.
  3. **Sistema:**
     - usuário `deploy` com chave SSH; acesso por senha e login do root desabilitados;
     - `unattended-upgrades`;
     - instalação do Docker Engine e do plugin compose pelo repositório oficial; `deploy` no grupo `docker`.
  4. **DNS:** registros `A` para `duofinance.dev.br` e `www` apontando para o IP estático; verificação com `dig`.
  5. **Segredos:**
     - criar `/opt/duo-finance/.env` a partir do `.env.example`;
     - gerar as chaves JWT com `gen-jwt-keys.sh`, a senha do banco com `openssl rand -base64 32`, a API key do Resend e `EMAIL_FROM`;
     - `chmod 600 .env`; o `.env` nunca vai para o git.
  6. **GHCR:** token de leitura de pacotes e `docker login ghcr.io` na instância.
  7. **Primeira subida e certificado:**
     - subir com o certificado autoassinado temporário;
     - emitir o certificado real com `certbot certonly --webroot` para os dois nomes;
     - recarregar o nginx e verificar com `curl -I https://duofinance.dev.br`;
     - conferir a nota SSL Labs.
  8. **GitHub:**
     - segredos e variáveis da Task 6;
     - environment `production`;
     - proteção da `main` com os checks da Task 5 obrigatórios;
     - primeiro deploy por merge.
  9. **Backup:**
     - bucket no Lightsail Object Storage, com chave de acesso restrita ao bucket e regra de ciclo de vida de 30 dias em `daily/`;
     - `install-cron.sh`;
     - teste manual de `backup.sh` e de `restore.sh` num banco descartável.
  10. **Admin:** promover o primeiro administrador com o `UPDATE users SET role = 'ADMIN' WHERE email = …` executado via `docker compose exec postgres psql`.
  11. **Operação:**
      - ver logs (`docker compose logs -f api`);
      - deploy manual de uma tag e volta manual;
      - restaurar um backup;
      - renovação do certificado (automática) e como verificá-la;
      - migração futura para o Managed Database (trocar `DB_*`).
  12. **Antes do lançamento:**
      - inserir o texto jurídico real nas páginas de privacidade e termos, com escape para vue-i18n;
      - revisar o `TERMS_VERSION`;
      - checar os e-mails reais (verificação, reset, convite, código) no Resend.

- [ ] **Step 1: Escrever o runbook** seguindo a ordem acima. Os comandos referenciam os arquivos reais das Tasks 1–7.
- [ ] **Step 2: Validar** — `npx markdownlint-cli2 docs/runbook.md` sem erros; cada script e variável citados existem (`grep` cruzado com `infra/` e `.env.example`).
- [ ] **Step 3: Commit**

```bash
git add docs/runbook.md README.md
git commit -m "docs: production provisioning and operations runbook"
```
