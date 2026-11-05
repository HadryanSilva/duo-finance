# Duo Finance — MVP Design

- **Data:** 2026-11-05
- **Status:** aprovado em conversa, aguardando revisão da spec escrita
- **Domínio:** `duofinance.dev.br`

## 1. Objetivo

Gerenciador financeiro web para casais, oferecido como **produto público com monetização futura**. O MVP roda como **beta fechado**: o cadastro exige um código de acesso, e o modelo de dados já prevê uma assinatura por casal, que será cobrada numa fase posterior. Dois usuários compartilham uma única entidade `Account`, que centraliza todas as informações financeiras do casal. A `Account` não representa uma conta bancária. Os usuários podem, opcionalmente, cadastrar contas bancárias para rastrear a origem e o destino de cada lançamento.

### Critérios de sucesso do MVP

1. Um visitante entra na lista de espera. Depois de aprovado, recebe um código de acesso, se cadastra, verifica o e-mail e usa o sistema sozinho imediatamente.
2. Ele convida o parceiro por e-mail; o parceiro aceita e ambos passam a ver os mesmos dados.
3. O casal registra receitas, despesas e transferências, com autor e conta bancária opcional.
4. Lançamentos recorrentes são gerados automaticamente.
5. O dashboard do mês mostra saldos, receitas × despesas, gastos por categoria e progresso dos orçamentos.
6. Qualquer membro pode sair da `Account` ou excluir a própria conta (LGPD).
7. A interface está disponível em pt-BR e inglês e funciona bem no desktop e no mobile web.
8. O administrador aprova a lista de espera e gerencia códigos de acesso, e o cadastro pode ser aberto ao público por configuração.

### Decisões de produto

| Tema | Decisão |
|---|---|
| Público | Produto público (LGPD aplicável), em beta fechado no MVP: cadastro por código de acesso obtido via lista de espera |
| Monetização | Futura. Assinatura por `Account` (o casal paga uma vez). No MVP, só o modelo e o ponto de verificação; sem cobrança |
| Visibilidade | Tudo compartilhado dentro da `Account`; o sistema registra o autor de cada lançamento |
| Vínculo | Usuário usa sozinho desde o cadastro, convida o parceiro, pode sair e pode excluir a conta |
| Moeda | Apenas EUR no MVP (moeda armazenada na `Account` para permitir evolução) |
| Idiomas | Multilíngue desde o início: pt-BR e en |
| Autenticação | E-mail + senha com JWT; modelo preparado para login social |
| Plataforma | Somente web; API REST pensada para um futuro app mobile |

### Escopo

**No MVP:** landing pública com lista de espera, cadastro por código de acesso, painel admin mínimo (lista de espera e códigos), modelo de assinatura com plano `BETA`, cadastro e autenticação, convite e vínculo do casal, contas bancárias, categorias, lançamentos (receita, despesa, transferência), lançamentos recorrentes, orçamento mensal por categoria, dashboard mensal, configurações (perfil, idioma, tema, saída, exclusão).

**Fora do MVP:** cobrança (provedor de pagamento, checkout, webhooks, planos pagos, paywall), cartão de crédito (fatura), parcelamento, metas, importação de extrato (OFX/CSV), login social, app mobile, multi-moeda, mesclagem de dados entre `Account`s, notificações de orçamento por e-mail, monitoramento externo de uptime.

## 2. Modelo de domínio

```
users ──1:N── user_identities        (LOCAL hoje; GOOGLE/APPLE no futuro)
  │
  └──1:1── account_members ──N:1── accounts ──1:1── subscriptions
                                   (máx. 2 membros)
                                       │
          ┌──────────────┬─────────────┼──────────────┬──────────────┐
     invitations   bank_accounts   categories    transactions   recurring_rules
                                       │              ▲               │
                                    budgets           └── gera ───────┘

waitlist_entries ──0:1── access_codes   (acesso ao beta; independentes da Account)
```

### Convenções

- Chaves primárias em UUID v7.
- Valores monetários em `BIGINT` de centavos (`*_cents`), sempre na moeda da `Account`.
- Datas de lançamento são `DATE`; timestamps são `TIMESTAMPTZ` em UTC.
- Toda entidade financeira carrega `account_id`, e toda consulta é filtrada pelo `account_id` do usuário autenticado.
- Saldos nunca são armazenados; são sempre calculados.

### Tabelas

**Identidade**

- `users`: `id`, `email` (único, comparação case-insensitive), `display_name`, `locale` (`pt-BR` | `en`), `email_verified_at` (nulo até verificar), `role` (`USER` | `ADMIN`), `failed_login_count`, `locked_until`, `terms_version`, `terms_accepted_at`, `created_at`, `updated_at`.
- `user_identities`: `id`, `user_id`, `provider` (`LOCAL`; futuramente `GOOGLE`, `APPLE`), `provider_subject` (para `LOCAL`, o e-mail normalizado), `password_hash` (só `LOCAL`, Argon2id), `created_at`. Único em `(provider, provider_subject)`.
- `refresh_tokens`: `id`, `user_id`, `family_id`, `token_hash`, `expires_at`, `revoked_at`, `replaced_by_id`, `created_at`.
- `user_tokens`: `id`, `user_id`, `type` (`EMAIL_VERIFICATION` | `PASSWORD_RESET`), `token_hash`, `expires_at`, `used_at`, `created_at`. Verificação expira em 24 h; reset em 1 h.

**Casal**

- `accounts`: `id`, `name`, `currency` (`CHAR(3)`, `EUR`), `created_at`, `updated_at`.
- `account_members`: `account_id`, `user_id` (único), `joined_at`. PK `(account_id, user_id)`.
- `invitations`: `id`, `account_id`, `email`, `token_hash`, `invited_by` (nulo se o autor for excluído), `status` (`PENDING` | `ACCEPTED` | `CANCELED` | `EXPIRED`), `expires_at` (7 dias), `created_at`, `accepted_at`. No máximo um convite `PENDING` por `Account`.
- `subscriptions`: `id`, `account_id` (único), `plan` (`BETA`; planos pagos virão depois), `status` (`TRIALING` | `ACTIVE` | `PAST_DUE` | `CANCELED` | `EXPIRED`), `current_period_end` (nulo no `BETA`), `provider` (`NONE`; futuramente o provedor de pagamento), `provider_customer_id`, `provider_subscription_id`, `created_at`, `updated_at`.

**Acesso ao beta**

- `waitlist_entries`: `id`, `email` (único, case-insensitive), `locale`, `status` (`PENDING` | `APPROVED` | `REJECTED`), `access_code_id` (preenchido na aprovação), `created_at`, `decided_at`.
- `access_codes`: `id`, `code_hash`, `email` (opcional; se preenchido, o código só vale para esse e-mail), `max_uses` (padrão 1), `used_count`, `expires_at` (opcional), `revoked_at`, `note`, `created_by` (admin), `created_at`. O código em texto puro é mostrado ou enviado uma única vez e nunca é armazenado.

**Finanças**

- `bank_accounts`: `id`, `account_id`, `name`, `type` (`CHECKING` | `SAVINGS` | `CASH` | `OTHER`), `initial_balance_cents` (pode ser negativo), `holder_user_id` (opcional, informativo), `archived_at`, `created_at`, `updated_at`.
- `categories`: `id`, `account_id`, `name`, `kind` (`INCOME` | `EXPENSE`), `color`, `icon`, `archived_at`, `created_at`, `updated_at`. Nome único por `(account_id, kind)` entre não arquivadas.
- `transactions`: `id`, `account_id`, `kind` (`INCOME` | `EXPENSE` | `TRANSFER`), `amount_cents` (> 0), `date`, `description`, `category_id`, `bank_account_id`, `destination_bank_account_id`, `created_by` (nulo após exclusão do autor), `recurring_rule_id`, `created_at`, `updated_at`. Único em `(recurring_rule_id, date)` quando `recurring_rule_id` não é nulo.
- `recurring_rules`: `id`, `account_id`, `kind`, `amount_cents`, `description`, `category_id`, `bank_account_id`, `destination_bank_account_id`, `frequency` (`WEEKLY` | `MONTHLY` | `YEARLY`), `start_date`, `end_date` (opcional), `next_run_date`, `active`, `created_by`, `created_at`, `updated_at`.
- `budgets`: `id`, `account_id`, `category_id` (único, categoria de `kind = EXPENSE`), `monthly_limit_cents` (> 0), `created_at`, `updated_at`.

### Regras de negócio

**Lançamentos**

- `INCOME` / `EXPENSE`: `category_id` obrigatório e do mesmo `kind`; `bank_account_id` opcional; `destination_bank_account_id` nulo.
- `TRANSFER`: `category_id` nulo; `bank_account_id` (origem) e `destination_bank_account_id` obrigatórios e diferentes. Transferências não entram nos totais de receita e despesa.
- Categorias e contas bancárias arquivadas não podem ser usadas em lançamentos novos, mas continuam aparecendo nos antigos.
- Uma categoria ou conta bancária só pode ser excluída fisicamente se não houver lançamentos nem regras que a referenciem; caso contrário, apenas arquivada.

**Saldo de conta bancária**

`saldo = initial_balance_cents + Σ INCOME + Σ TRANSFER recebidas − Σ EXPENSE − Σ TRANSFER enviadas`, considerando lançamentos com `date <= hoje`.

**Recorrência**

- Um job diário (`@Scheduled`, 00:15 UTC) gera, para cada regra ativa, todas as ocorrências com data `<= hoje` a partir de `next_run_date` e avança `next_run_date`.
- A geração é idempotente pela chave única `(recurring_rule_id, date)`.
- `MONTHLY` em dia inexistente (ex.: 31) usa o último dia do mês.
- Ao criar uma regra com `start_date <= hoje`, a primeira ocorrência é gerada imediatamente.
- Editar uma regra afeta apenas ocorrências futuras. Desativar ou excluir uma regra não apaga lançamentos já gerados (que passam a ter `recurring_rule_id = NULL` na exclusão).
- Lançamentos gerados podem ser editados ou excluídos individualmente.

**Orçamento**

- Um limite mensal fixo por categoria de despesa.
- Progresso do mês = Σ `EXPENSE` da categoria no mês ÷ limite.
- Estados exibidos na interface: normal (< 80%), atenção (≥ 80%) e estourado (≥ 100%).

**Acesso ao beta**

- O modo de cadastro é definido pela configuração `app.registration.mode`: `INVITE_ONLY` (padrão no MVP) ou `OPEN`.
- Em `INVITE_ONLY`, o cadastro exige **uma** destas credenciais:
  - um código de acesso válido (não revogado, não expirado, `used_count < max_uses` e, se o código tiver e-mail, igual ao e-mail do cadastro);
  - um token de convite de casal `PENDING` para o mesmo e-mail. O parceiro convidado não precisa de código.
- O consumo do código (`used_count + 1`) acontece na mesma transação do cadastro, com lock na linha do código.
- Lista de espera: qualquer visitante deixa e-mail e idioma. A resposta é sempre a mesma, exista o e-mail ou não. Quando o admin aprova uma entrada, o sistema gera um código de uso único vinculado ao e-mail e o envia no idioma da entrada.
- Admins (`users.role = ADMIN`) são promovidos manualmente no banco. Não há cadastro de admin pela interface.

**Assinatura**

- Toda `Account` nasce com uma `subscription` `plan = BETA`, `status = ACTIVE`, `provider = NONE`.
- O `EntitlementService` é o único ponto que decide se uma `Account` tem acesso. No MVP, acesso = `status` em `TRIALING` ou `ACTIVE`. Um interceptor aplica essa verificação a todas as rotas financeiras (`bank-accounts`, `categories`, `transactions`, `recurring-rules`, `budgets`, `dashboard`), que respondem `402` com `code = SUBSCRIPTION_INACTIVE` quando não há acesso. As rotas `auth`, `me`, `account` e de lista de espera não são bloqueadas, para que o usuário consiga sair, excluir a conta ou, no futuro, pagar.
- O job de recorrência ignora `Account`s sem acesso.
- Os dados da assinatura são da `Account`: os dois membros compartilham o acesso.
- Quando a cobrança existir, apagar uma `Account` com assinatura paga (aceite de convite ou exclusão de usuário) precisará cancelar a assinatura no provedor. Isso fica para a fase de cobrança.

**Vínculo do casal**

- **Cadastro:** cria `User`, `UserIdentity(LOCAL)`, uma `Account` solo (nome padrão a partir do `display_name`), a `subscription` `BETA` e as categorias padrão no idioma do usuário.
- **Convite:** só membros com e-mail verificado podem convidar; só é possível quando a `Account` tem 1 membro; envia e-mail com link contendo o token.
- **Aceite:** exige estar autenticado com o e-mail convidado. Se a `Account` solo do convidado tiver qualquer dado além das categorias padrão, a API exige confirmação explícita (`confirmDiscard = true`), e essa `Account` é apagada junto com a sua `subscription`. O limite de 2 membros é verificado com lock pessimista na `Account` de destino.
- **Sair:** o membro que sai recebe uma `Account` nova e vazia (com categorias padrão e `subscription` `BETA`); os dados e a assinatura existente permanecem com quem fica. Só é permitido quando a `Account` tem 2 membros.
- **Excluir usuário:** apaga `users`, identidades e tokens. Se ele era o único membro, a `Account` e todos os dados são apagados. Caso contrário, `created_by` e `invited_by` passam a `NULL` e a interface mostra "Ex-membro".

## 3. Backend

### Stack

- Java 25, Spring Boot 4 (Spring Framework 7), virtual threads habilitadas.
- Spring Web MVC, Spring Data JPA (Hibernate 7), Bean Validation, Flyway.
- Spring Security com OAuth2 Resource Server (validação de JWT RS256 emitido pela própria aplicação).
- Thymeleaf (somente templates de e-mail), springdoc-openapi.
- Maven Wrapper. Testes com JUnit 5, AssertJ, Mockito e Testcontainers.
- Pacote base: `br.dev.duofinance`.

### Organização

Pacotes por funcionalidade, com camadas explícitas em cada um:

```
br.dev.duofinance
├── auth/          controller · service · repository · dto · model
├── user/
├── account/       (casal, membros, convites)
├── bankaccount/
├── category/
├── transaction/
├── recurring/
├── budget/
├── subscription/  (assinatura e EntitlementService)
├── access/        (lista de espera, códigos de acesso, endpoints admin)
├── dashboard/     (controller · service · dto; apenas consultas agregadas)
└── shared/        (security, email, error, config)
```

Regras entre camadas:

- **Controller:** recebe e devolve DTOs (records). Nunca expõe entidades. Sem lógica de negócio.
- **Service:** regras de negócio e fronteiras transacionais (`@Transactional`). Obtém o `accountId` do usuário autenticado e o passa adiante.
- **Repository:** Spring Data JPA, um por agregado; métodos sempre recebem `accountId`.
- **Dto:** records de request e response, com Bean Validation nos requests e mapeamento manual (`static from(Entity)`).
- **Model:** entidades JPA.

### API REST (`/api/v1`)

| Recurso | Operações |
|---|---|
| `public` | `GET registration-mode` (`INVITE_ONLY` \| `OPEN`); `POST waitlist` (e-mail, idioma) |
| `auth` | `POST register` (com `accessCode` ou `invitationToken` em `INVITE_ONLY`), `login`, `refresh`, `logout`, `verify-email`, `resend-verification`, `forgot-password`, `reset-password` |
| `me` | `GET`, `PATCH` (nome, idioma), `PUT password`, `DELETE` (exclusão LGPD) |
| `account` | `GET`, `PATCH` (nome); `POST/DELETE invitations`; `GET invitations/{token}` (prévia pública); `POST invitations/{token}/accept`; `POST leave` |
| `bank-accounts` | CRUD + `POST {id}/archive`, `POST {id}/unarchive`; listagem inclui saldo calculado |
| `categories` | CRUD + arquivar/desarquivar |
| `transactions` | CRUD; `GET` com filtros `from`, `to`, `kind`, `categoryId`, `bankAccountId`, `createdBy`, `q`; paginação `page`/`size` |
| `recurring-rules` | CRUD + ativar/desativar |
| `budgets` | CRUD |
| `dashboard` | `GET ?month=YYYY-MM`: saldos por conta e total, receitas, despesas, gastos por categoria, orçamentos com progresso, próximas recorrências (30 dias) |
| `account/subscription` | `GET`: plano, status e se há acesso (somente leitura no MVP) |
| `admin/waitlist` | `GET` (filtro por status, paginação); `POST {id}/approve` (gera código e envia e-mail); `POST {id}/reject` |
| `admin/access-codes` | `GET`; `POST` (gera código avulso com `email`, `maxUses`, `expiresAt`, `note` opcionais; devolve o código em texto puro uma única vez); `POST {id}/revoke` |

Recursos de outra `Account` respondem `404` (nunca `403`) para não revelar existência.

### Segurança

- Senhas com Argon2id; mínimo de 8 caracteres.
- Access token JWT RS256 de 15 min com claims `sub` (userId), `acc` (accountId) e `role`. Rotas `/api/v1/admin/**` exigem `role = ADMIN`. Como o `accountId` muda ao sair ou aceitar convite, essas operações devolvem um novo par de tokens.
- Refresh token opaco de 30 dias, armazenado com hash, rotativo a cada uso. Reuso de um token já rotacionado revoga toda a família.
- Na web, o refresh token vai em cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`. O access token vai no corpo da resposta e fica só em memória no frontend.
- E-mail não verificado permite o uso normal, mas bloqueia o envio de convites.
- Após 5 logins falhos consecutivos, a conta fica bloqueada por 15 min. Respostas de login, cadastro e esqueci-senha são genéricas e não revelam se o e-mail existe.
- Preparação para login social: o login local é apenas um `AuthenticationProvider` que resolve `UserIdentity(LOCAL)`. A emissão de tokens fica num `TokenService` independente, reutilizável por futuros provedores OIDC.

### Erros

`@RestControllerAdvice` devolve `ProblemDetail` (RFC 9457) com campos extras `code` (estável, ex.: `ACCOUNT_FULL`, `INVITE_EXPIRED`, `CATEGORY_ARCHIVED`, `ACCESS_CODE_INVALID`, `SUBSCRIPTION_INACTIVE`, `VALIDATION_FAILED`) e, para validação, `errors[]` com `field` e `code`. O frontend traduz `code` via i18n.

### E-mail

- Interface `EmailSender` com implementações `ResendEmailSender` (produção, API HTTP do Resend via `RestClient`) e `LoggingEmailSender` (dev e testes).
- Templates Thymeleaf por idioma: verificação, reset de senha, convite do casal e código de acesso ao beta.
- Envio assíncrono após o commit (`@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`). Uma falha no envio é registrada em log e não desfaz a operação. O usuário pode reenviar a verificação, o convite pode ser recriado e o admin pode gerar um novo código para uma entrada aprovada.

## 4. Frontend

### Stack

- Vue 3 (última estável), Vite, TypeScript estrito, Composition API com `<script setup>`.
- Vue Router, Pinia (sessão e estado de UI), TanStack Query para Vue (dados do servidor).
- vue-i18n (pt-BR, en); valores e datas formatados com `Intl` conforme o idioma, em EUR.
- Tailwind CSS v4 + shadcn-vue (Reka UI); vue-echarts para gráficos.
- VeeValidate + Zod para formulários.
- Vitest + Vue Test Utils; Playwright para E2E; ESLint + Prettier.

### Direção visual (escolhida via mockup: opção A)

- "Fintech sóbria e confiável", no estilo Stripe/Mercury.
- Cor de marca indigo `#4F46E5`. Verde e vermelho reservados exclusivamente para receita e despesa; amarelo para alerta de orçamento.
- Fonte única Inter, com escala tipográfica em tokens; valores com `tabular-nums`.
- Superfícies claras com bordas finas; modo escuro equivalente.
- Logo SVG com dois círculos sobrepostos.
- Layout com sidebar no desktop e navegação inferior no mobile.
- Todas as cores, tamanhos e espaçamentos vêm de tokens: nada de hex ou tamanhos avulsos nos componentes.

### Telas

0. **Landing pública** (`/`): proposta do produto, formulário de lista de espera e link para entrar; links para política de privacidade e termos.
1. **Autenticação:** login, cadastro (campo de código de acesso, oculto quando vindo de um convite de casal ou com `registration-mode = OPEN`), verificação de e-mail, esqueci e redefinir senha, aceitar convite.
2. **Onboarding** (após o primeiro login): nome do casal → convidar parceiro (pulável) → primeira conta bancária (pulável).
3. **Dashboard** mensal com seletor de mês: saldo total e por conta, receitas × despesas, gastos por categoria (rosca), orçamentos com barras de progresso, próximas recorrências, últimos lançamentos.
4. **Lançamentos:** lista agrupada por dia com avatar do autor, filtros e busca; painel lateral para criar ou editar receita, despesa ou transferência.
5. **Contas bancárias**, **Categorias**, **Orçamentos**, **Recorrências:** listas com criação e edição em painel lateral.
6. **Configurações:** perfil, senha, idioma, tema, casal (convite pendente, sair), plano atual (somente leitura: "Beta") e excluir conta.
7. **Admin** (visível só para `ADMIN`): lista de espera com aprovar e rejeitar; códigos de acesso com gerar, listar e revogar.
8. **Acesso suspenso:** exibida quando a API responde `SUBSCRIPTION_INACTIVE`. No MVP é só uma mensagem com links para configurações e logout; o paywall vem com a cobrança.

### Organização

```
src/
├── app/        router, layouts, providers
├── features/   auth, onboarding, dashboard, transactions, bank-accounts,
│               categories, budgets, recurring, settings, landing, admin
│               └── <feature>/{api,components,pages}
└── shared/     ui (componentes base), lib (http, money, dates), i18n
```

### Sessão

- Ao iniciar, o app chama `POST /auth/refresh` para restaurar a sessão pelo cookie.
- Um interceptor HTTP injeta o access token. Ao receber `401`, faz um único refresh compartilhado entre requisições simultâneas e as repete; se o refresh falhar, redireciona ao login.
- Frontend e API ficam na mesma origem (via Nginx), então não há CORS em produção.

## 5. Infraestrutura e deploy

### Repositório

```
duo-finance/
├── backend/     Spring Boot (Maven Wrapper, Dockerfile multi-stage → JRE 25)
├── frontend/    Vue + Vite
├── infra/       docker-compose.yml, docker-compose.dev.yml, nginx/, scripts/
└── docs/        specs, planos
```

### Produção

Uma instância AWS Lightsail (Ubuntu LTS, 2 GB RAM) com Docker Compose:

- **nginx:** imagem própria contendo o build do Vue; fallback SPA para `index.html`; proxy de `/api/` para `api:8080`; TLS (Let's Encrypt via certbot, renovação automática) para `duofinance.dev.br` com redirect de `www` e de HTTP para HTTPS; `limit_req` nas rotas `/api/v1/auth/` e `/api/v1/public/`; headers HSTS, CSP, `X-Content-Type-Options`, `Referrer-Policy`.
- **api:** Spring Boot, exposto apenas na rede interna do compose.
- **postgres:16:** volume nomeado, apenas rede interna.
- Firewall Lightsail: portas 22 (restrita ao IP do administrador), 80 e 443.
- Segredos (senha do banco, chave privada RSA do JWT, API key do Resend) num `.env` na instância, fora do git.
- Preparado para migrar o banco para o Lightsail Managed Database trocando apenas o datasource.

### CI/CD (GitHub Actions)

- **Pull request:** testes do backend (com Testcontainers) e do frontend, lint e type-check.
- **Merge na `main`:** build das imagens `api` e `nginx` com tag do commit, push para o GHCR, deploy via SSH (`docker compose pull && docker compose up -d`) e health check em `/api/actuator/health`. Em caso de falha, volta para a tag anterior.
- Migrations do Flyway são aplicadas no startup do backend.

### Backup e observabilidade

- `pg_dump` diário compactado, enviado ao Lightsail Object Storage com retenção de 30 dias; snapshot automático semanal da instância.
- Logs em JSON no stdout, com rotação do driver do Docker.
- Actuator expõe apenas `health`.

### Desenvolvimento local

- `docker-compose.dev.yml` sobe apenas o Postgres 16.
- Backend roda pela IDE (perfil `dev`, com `LoggingEmailSender`, que imprime no log os e-mails e links de verificação, reset e convite); frontend com `vite dev` e proxy de `/api` para `localhost:8080`.

## 6. Testes e qualidade

- **Backend, unitários:** services com foco nas regras (limite de membros, convite e aceite, sair e excluir, transferências, saldo, geração de recorrências incluindo fim de mês, orçamento).
- **Backend, integração:** `@SpringBootTest` + Testcontainers (Postgres 16) cobrindo controllers, segurança (fluxo completo de tokens, reuso de refresh) e migrations.
- **Isolamento entre `Account`s:** suíte dedicada garante que toda rota com ID de outra `Account` responde `404`.
- **Beta e assinatura:** cadastro sem credencial em `INVITE_ONLY` falha; código esgotado, expirado, revogado ou de outro e-mail falha; parceiro convidado se cadastra sem código; consumo concorrente de um código de uso único só aceita um cadastro; `Account` com assinatura inativa recebe `402` nas rotas financeiras e não é bloqueada em `auth`, `me` e `account`; rotas admin exigem `ADMIN`.
- **Frontend:** Vitest para componentes e composables (formatação de dinheiro, interceptor de refresh); Playwright para cadastro → onboarding → lançamento → dashboard e convite → aceite.
- **Processo:** TDD nas regras de domínio; Spotless no Java, ESLint + Prettier no frontend. Cobertura não é meta; regra crítica sem teste bloqueia o PR.

## 7. LGPD

- Consentimento com a política de privacidade e os termos no cadastro (versão aceita registrada em `users`: `terms_version`, `terms_accepted_at`).
- Exclusão de conta pelo próprio usuário (seção 2, regras de vínculo).
- Dados mínimos: nome de exibição e e-mail. Sem rastreadores de terceiros no frontend.
- As páginas de política de privacidade e termos de uso são estáticas no frontend; o texto jurídico é fornecido pelo responsável pelo produto.
