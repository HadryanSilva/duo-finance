# Backend Foundation, Identity & Access — Implementation Plan (1 de 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar a API Spring Boot com fundação (erros, segurança, e-mail), autenticação JWT completa, beta fechado (lista de espera e códigos de acesso), vínculo do casal (convite, aceite, saída, exclusão), modelo de assinatura com `EntitlementService` e endpoints admin, tudo testado contra Postgres 16 real.

**Architecture:** Monolito Spring MVC em `backend/`, pacotes por funcionalidade (`auth`, `user`, `account`, `category`, `subscription`, `access`, `shared`) com subpacotes `controller · service · repository · dto · model`. Schema versionado com Flyway; integridade referencial (cascatas e `SET NULL`) fica no banco. O `accountId` autoritativo de cada requisição vem do banco, não do JWT.

**Tech Stack:** Java 25, Spring Boot 4.1.2 (Spring Framework 7, Hibernate 7, Jackson 3), Spring Security + OAuth2 Resource Server (Nimbus), Flyway, PostgreSQL 16, Thymeleaf (e-mails), springdoc-openapi 3.x, BouncyCastle (Argon2), JUnit 5, AssertJ, Testcontainers, MockMvcTester.

**Spec:** `docs/superpowers/specs/2026-11-05-duo-finance-mvp-design.md`

**Sequência de planos:** (1) este; (2) backend — domínio financeiro; (3) frontend; (4) infra e deploy.

## Global Constraints

- Pacote base `br.dev.duofinance`; Maven Wrapper; Java 25; Spring Boot 4.1.2.
- PKs UUID v7 (`@UuidGenerator(style = UuidGenerator.Style.VERSION_7)` do Hibernate 7).
- Timestamps `TIMESTAMPTZ` em UTC via `Instant`; datas de negócio `LocalDate`. Um bean `Clock` (UTC) é injetado onde houver tempo, para testes controlarem o relógio.
- E-mail normalizado com `trim().toLowerCase(Locale.ROOT)` antes de salvar, comparar ou fazer hash; coluna `email` com índice único sobre o valor normalizado.
- Tokens opacos (refresh, verificação, reset, convite): 32 bytes aleatórios em base64url; no banco só o hash SHA-256 em hex.
- Senhas: Argon2id (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`), mínimo 8 e máximo 128 caracteres.
- Access token JWT RS256, 15 min, claims `sub` (userId), `acc` (accountId), `role`.
- Refresh token: 30 dias, rotativo, cookie `df_refresh`, `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`.
- Expirações: verificação de e-mail 24 h; reset de senha 1 h; convite de casal 7 dias; código de acesso de lista de espera 30 dias.
- Bloqueio de login: 5 falhas consecutivas → bloqueado por 15 min.
- Idiomas aceitos: `pt-BR`, `en`. Moeda da `Account`: `EUR`.
- Erros: `ProblemDetail` (RFC 9457) com `code` estável e, em validação, `errors[]` (`field`, `code`).
- Recursos de outra `Account` respondem `404`, nunca `403`.
- Rotas financeiras (`/api/v1/bank-accounts/**`, `/categories/**`, `/transactions/**`, `/recurring-rules/**`, `/budgets/**`, `/dashboard/**`) passam pelo `EntitlementService` e respondem `402 SUBSCRIPTION_INACTIVE` sem acesso.
- Toda tabela de dados de uma `Account` referencia `accounts(id) ON DELETE CASCADE`; toda coluna de autor (`created_by`, `invited_by`) referencia `users(id) ON DELETE SET NULL`. O plano 2 segue a mesma regra.
- Comandos rodam a partir de `backend/`. Docker precisa estar ativo para os testes (Testcontainers).

## Review Focus

1. **E-mail com maiúsculas e espaços** (`" Ana@Mail.com"`): cadastro, login, convite, lista de espera e código vinculado devem tratá-lo como `ana@mail.com`. Teste em Task 7 (`register_normalizesEmail`) e Task 11 (`accept_matchesInviteEmailIgnoringCase`).
2. **Duas abas renovando a sessão ao mesmo tempo**: reuso de um refresh token rotacionado há menos de 10 s responde `401 REFRESH_RACE` **sem** revogar a família, para não deslogar o usuário. Teste em Task 6 (`refresh_reuseWithinGraceWindow_doesNotRevokeFamily`).
3. **Access token antigo após sair ou aceitar convite**: um token com `acc` da `Account` anterior deve receber `401 SESSION_STALE`, e não acessar dados que já não são do usuário. Teste em Task 4 (`staleAccountClaim_isRejected`).
4. **Cadastro que falha depois de validar o código** (senha fraca, e-mail já usado): o código não pode ser consumido. Teste em Task 7 (`register_failureAfterCodeCheck_doesNotConsumeCode`).
5. **Aceitar convite estando num casal completo, ou o próprio convite**: `409 ALREADY_IN_COUPLE` e `409 INVITE_SELF`. Testes em Task 11.

---

## File Structure

```
backend/
├── pom.xml, mvnw, .mvn/
├── src/main/resources/
│   ├── application.yml, application-dev.yml
│   ├── db/migration/V1__identity.sql, V2__accounts.sql, V3__beta_access.sql
│   ├── templates/email/verify-email.html, reset-password.html, couple-invitation.html, access-code.html
│   └── i18n/email_pt_BR.properties, email_en.properties
├── src/main/java/br/dev/duofinance/
│   ├── DuoFinanceApplication.java
│   ├── shared/config/      AppProperties, ClockConfig, AsyncConfig
│   ├── shared/error/       ErrorCode, ApiException, GlobalExceptionHandler
│   ├── shared/security/    SecurityConfig, JwtKeyConfig, CurrentUser, CurrentUserArgumentResolver, WebConfig
│   ├── shared/token/       SecureTokens
│   ├── shared/email/       EmailSender, EmailMessage, LoggingEmailSender, ResendEmailSender,
│   │                       EmailTemplateRenderer, EmailRequestedEvent, EmailDispatchListener
│   ├── user/               model(User, UserIdentity, UserRole, IdentityProvider) · repository · service(MeService) · controller(MeController) · dto
│   ├── auth/               model(RefreshToken, UserToken, UserTokenType) · repository ·
│   │                       service(TokenService, AuthSessionService, RegistrationService, LoginService,
│   │                       EmailVerificationService, PasswordResetService) · controller(AuthController, RefreshCookies) · dto
│   ├── account/            model(Account, AccountMember, Invitation, InvitationStatus) · repository ·
│   │                       service(AccountProvisioningService, AccountService, InvitationService, MembershipService,
│   │                       AccountDataProbe, AccountDeletionService) · controller(AccountController, InvitationController) · dto
│   ├── category/           model(Category, CategoryKind) · repository · service(DefaultCategories, DefaultCategorySeeder, CustomCategoryProbe)
│   ├── subscription/       model(Subscription, SubscriptionPlan, SubscriptionStatus, PaymentProvider) · repository ·
│   │                       service(SubscriptionService, EntitlementService) · controller(SubscriptionController, EntitlementInterceptor) · dto
│   └── access/             model(WaitlistEntry, WaitlistStatus, AccessCode) · repository ·
│                           service(AccessCodeService, WaitlistService, RegistrationGate) ·
│                           controller(PublicController, AdminWaitlistController, AdminAccessCodeController) · dto
└── src/test/java/br/dev/duofinance/
    ├── TestcontainersConfiguration.java, IntegrationTest.java (anotação meta)
    ├── support/            RecordingEmailSender, MutableClock, TestUsers
    └── <mesma estrutura de pacotes>  …Test.java
infra/docker-compose.dev.yml
```

Testes de integração usam a meta-anotação `@IntegrationTest` (= `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfiguration)` + `@ActiveProfiles("test")`), injetam `MockMvcTester mvc` e recebem `RecordingEmailSender` e `MutableClock` como beans `@Primary` do perfil `test`. O banco é limpo entre testes por um `@BeforeEach` em `IntegrationTest` que faz `TRUNCATE ... CASCADE` em todas as tabelas, exceto `flyway_schema_history`.

---

### Task 1: Scaffold do backend com Postgres e teste de integração

**Files:**
- Create: `backend/` (via Spring Initializr), `infra/docker-compose.dev.yml`, `backend/src/main/resources/application.yml`, `application-dev.yml`, `backend/src/test/resources/application-test.yml`
- Create: `backend/src/test/java/br/dev/duofinance/TestcontainersConfiguration.java`, `IntegrationTest.java`, `support/MutableClock.java`
- Create: `backend/src/main/java/br/dev/duofinance/shared/config/ClockConfig.java`, `AppProperties.java`
- Test: `backend/src/test/java/br/dev/duofinance/HealthEndpointTest.java`

**Interfaces:**
- Produces: `@IntegrationTest`; `MutableClock extends Clock` com `setInstant(Instant)` e `advance(Duration)`; bean `Clock`; `AppProperties` record ligado a `app.*`:
  `publicBaseUrl: URI`, `registration.mode: RegistrationMode (INVITE_ONLY|OPEN)`, `auth.jwtPrivateKey`/`jwtPublicKey` (PEM, opcionais), `auth.cookieSecure: boolean`, `email.from: String`, `email.provider: (LOG|RESEND)`, `email.resendApiKey: String`.

- [ ] **Step 1: Gerar o projeto**

```bash
curl -s https://start.spring.io/starter.zip -d type=maven-project -d language=java \
  -d bootVersion=4.1.2 -d javaVersion=25 -d groupId=br.dev.duofinance -d artifactId=backend \
  -d name=duo-finance -d packageName=br.dev.duofinance \
  -d dependencies=web,data-jpa,flyway,postgresql,validation,security,oauth2-resource-server,actuator,thymeleaf,testcontainers \
  -o backend.zip && unzip backend.zip -d backend && rm backend.zip
```

Renomeie a classe principal para `DuoFinanceApplication`. Adicione ao `pom.xml`: `org.bouncycastle:bcprov-jdk18on` (Argon2), `org.springdoc:springdoc-openapi-starter-webmvc-ui` (última 3.x compatível com Boot 4.1) e o plugin `com.diffplug.spotless:spotless-maven-plugin` com `palantirJavaFormat`.

- [ ] **Step 2: Configuração**

`application.yml`: `spring.threads.virtual.enabled: true`, `spring.jpa.open-in-view: false`, `spring.jpa.hibernate.ddl-auto: validate`, `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`, `management.endpoints.web.exposure.include: health`, `management.endpoints.web.base-path: /api/actuator`, `app.registration.mode: INVITE_ONLY`, `app.email.provider: RESEND`, `app.auth.cookie-secure: true`. O datasource vem de `DB_URL`/`DB_USER`/`DB_PASSWORD`.
`application-dev.yml`: datasource `jdbc:postgresql://localhost:5432/duofinance` (`duofinance`/`duofinance`), `app.email.provider: LOG`, `app.public-base-url: http://localhost:5173`.
`application-test.yml`: `app.email.provider: LOG`, `app.public-base-url: http://localhost:5173`.
`infra/docker-compose.dev.yml`: serviço `postgres` com `postgres:16`, porta `5432`, volume nomeado `pgdata`, credenciais iguais ao perfil dev.
`TestcontainersConfiguration`: `@Bean @ServiceConnection PostgreSQLContainer postgres()` com imagem `postgres:16`.

- [ ] **Step 3: Escrever o teste que falha**

```java
@IntegrationTest
class HealthEndpointTest {
    @Autowired MockMvcTester mvc;

    @Test
    void health_isUpAndPublic() {
        assertThat(mvc.get().uri("/api/actuator/health"))
            .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("UP");
    }
}
```

- [ ] **Step 4: Rodar e ver falhar**

Run: `./mvnw test -Dtest=HealthEndpointTest`
Expected: FAIL (`401` da segurança padrão, ou erro de contexto antes de existir `@IntegrationTest`).

- [ ] **Step 5: Implementar**

Crie `@IntegrationTest`, `MutableClock` (bean `@Primary` num `@TestConfiguration` importado pela meta-anotação) e `ClockConfig` (`Clock.systemUTC()`). Ainda não há `SecurityConfig`: libere `/api/actuator/health` com um `SecurityFilterChain` mínimo, que a Task 4 substitui.

- [ ] **Step 6: Rodar e ver passar**

Run: `./mvnw test -Dtest=HealthEndpointTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend infra/docker-compose.dev.yml
git commit -m "chore(backend): scaffold Spring Boot 4.1 project with Postgres 16 test setup"
```

---

### Task 2: Tratamento de erros com ProblemDetail

**Files:**
- Create: `shared/error/ErrorCode.java`, `ApiException.java`, `GlobalExceptionHandler.java`
- Test: `shared/error/GlobalExceptionHandlerTest.java` (com um `@RestController` interno, só de teste, em `/api/v1/__test/errors`)

**Interfaces:**
- Produces:
  - `enum ErrorCode { VALIDATION_FAILED(400), UNAUTHENTICATED(401), INVALID_CREDENTIALS(401), SESSION_STALE(401), REFRESH_TOKEN_INVALID(401), REFRESH_RACE(401), SUBSCRIPTION_INACTIVE(402), FORBIDDEN(403), NOT_FOUND(404), EMAIL_ALREADY_REGISTERED(409), ACCESS_CODE_INVALID(400), INVITE_INVALID(400), INVITE_EXPIRED(410), INVITE_SELF(409), INVITE_PENDING_EXISTS(409), ACCOUNT_FULL(409), ALREADY_IN_COUPLE(409), ACCOUNT_HAS_DATA(409), NOT_IN_COUPLE(409), EMAIL_NOT_VERIFIED(403), TOKEN_INVALID(400) }`, cada um com `HttpStatus status()`. O plano 2 acrescenta códigos ao mesmo enum.
  - `class ApiException extends RuntimeException` com `ApiException(ErrorCode code)` e `ApiException(ErrorCode code, String detail)`; `ErrorCode code()`.
  - Corpo de erro: `type` = `https://duofinance.dev.br/errors/<code-kebab>`, `title`, `status`, `detail`, `code`, e `errors: [{field, code}]` para validação (`code` = nome da constraint, ex.: `NotBlank`, `Email`, `Size`).

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void apiException_rendersProblemDetailWithCode() {
    assertThat(mvc.get().uri("/api/v1/__test/errors/account-full"))
        .hasStatus(409)
        .bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.code").isEqualTo("ACCOUNT_FULL");
            json.assertThat().extractingPath("$.status").isEqualTo(409);
        });
}
@Test void beanValidation_rendersFieldErrors() {
    assertThat(mvc.post().uri("/api/v1/__test/errors/validate")
            .contentType(APPLICATION_JSON).content("{\"name\":\"\"}"))
        .hasStatus(400)
        .bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
            json.assertThat().extractingPath("$.errors[0].field").isEqualTo("name");
            json.assertThat().extractingPath("$.errors[0].code").isEqualTo("NotBlank");
        });
}
@Test void unexpectedException_returns500WithoutStackTrace() { /* status 500, $.code == "INTERNAL_ERROR", corpo sem "Exception" */ }
@Test void malformedJson_returns400ValidationFailed() { /* corpo "{" → 400, code VALIDATION_FAILED */ }
```

Acrescente `INTERNAL_ERROR(500)` ao enum. Como o controller de teste fica em `src/test`, libere `/api/v1/__test/**` no `SecurityFilterChain` mínimo da Task 1. A Task 4 mantém essa liberação apenas quando o perfil `test` está ativo.

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL (classes inexistentes).

- [ ] **Step 3: Implementar `GlobalExceptionHandler extends ResponseEntityExceptionHandler`**

Ele trata `ApiException`, `MethodArgumentNotValidException`, `HttpMessageNotReadableException` e `Exception` (este último gera log `ERROR` com stack trace e uma resposta genérica).

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): RFC 9457 problem details with stable error codes"
```

---

### Task 3: Schema de identidade e utilitários de token

**Files:**
- Create: `db/migration/V1__identity.sql`
- Create: `user/model/User.java`, `UserIdentity.java`, `UserRole.java`, `IdentityProvider.java`; `user/repository/UserRepository.java`, `UserIdentityRepository.java`
- Create: `auth/model/RefreshToken.java`, `UserToken.java`, `UserTokenType.java`; `auth/repository/RefreshTokenRepository.java`, `UserTokenRepository.java`
- Create: `shared/token/SecureTokens.java`
- Test: `shared/token/SecureTokensTest.java`, `user/repository/UserRepositoryTest.java`

**Interfaces:**
- Produces:
  - `V1__identity.sql` com as tabelas `users`, `user_identities`, `refresh_tokens` e `user_tokens`, exatamente com as colunas da spec §2. Detalhes: `users.role` default `'USER'`; `users.failed_login_count INT NOT NULL DEFAULT 0`; `UNIQUE(provider, provider_subject)`; FKs para `users(id) ON DELETE CASCADE`; índice único em `refresh_tokens.token_hash` e em `user_tokens.token_hash`; índice em `refresh_tokens.family_id`.
  - `UserRepository.findByEmail(String normalizedEmail): Optional<User>`.
  - `UserIdentityRepository.findByProviderAndProviderSubject(IdentityProvider, String): Optional<UserIdentity>`.
  - `RefreshTokenRepository.findByTokenHashForUpdate(String)` (`@Lock(PESSIMISTIC_WRITE)`), `revokeFamily(UUID familyId, Instant now)`, `revokeAllForUser(UUID userId, Instant now)` (ambos `@Modifying`).
  - `UserTokenRepository.findByTokenHashAndType(String, UserTokenType)`, `invalidateUnused(UUID userId, UserTokenType type, Instant now)`.
  - `SecureTokens.generate(): String` (32 bytes, base64url sem padding), `SecureTokens.sha256Hex(String): String`, `SecureTokens.normalizeEmail(String): String`.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void generate_isUrlSafeAnd43Chars() {
    assertThat(SecureTokens.generate()).hasSize(43).matches("[A-Za-z0-9_-]+");
}
@Test void sha256Hex_isStable() {
    assertThat(SecureTokens.sha256Hex("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
}
@Test void normalizeEmail_trimsAndLowercases() {
    assertThat(SecureTokens.normalizeEmail("  Ana@Mail.COM ")).isEqualTo("ana@mail.com");
}
// UserRepositoryTest (@IntegrationTest)
@Test void duplicateEmail_violatesUniqueConstraint() { /* salvar 2 users com "ana@mail.com" → DataIntegrityViolationException */ }
@Test void deletingUser_cascadesIdentitiesAndTokens() { /* user + identity + refresh token; delete user; repos vazios */ }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest='SecureTokensTest,UserRepositoryTest'`
Expected: FAIL.

- [ ] **Step 3: Implementar a migration, as entidades e os repositórios**

`User` normaliza o e-mail no setter/construtor via `SecureTokens.normalizeEmail`. Enums são mapeados com `@Enumerated(STRING)`.

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest='SecureTokensTest,UserRepositoryTest'`
Expected: PASS (o Flyway aplica V1, e o `ddl-auto: validate` confere entidades contra o schema).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): identity schema, entities and secure token utilities"
```

---

### Task 4: Segurança — emissão de JWT e resolução do usuário atual

**Files:**
- Create: `shared/security/JwtKeyConfig.java`, `SecurityConfig.java`, `CurrentUser.java`, `CurrentUserArgumentResolver.java`, `WebConfig.java`
- Create: `auth/service/TokenService.java`
- Create (antecipado da Task 8, para o resolver): `db/migration/V2__accounts.sql` com **apenas** `accounts` e `account_members`; `account/model/Account.java`, `AccountMember.java`; `account/repository/AccountRepository.java`, `AccountMemberRepository.java`
- Test: `shared/security/SecurityTest.java` (controller de teste em `/api/v1/__test/me` que devolve o `CurrentUser`, e `/api/v1/admin/__test` que devolve 200)

**Interfaces:**
- Produces:
  - `record CurrentUser(UUID userId, UUID accountId, UserRole role)`, resolvido como parâmetro de controller.
  - `TokenService.issueAccessToken(User user, UUID accountId): IssuedAccessToken` com `record IssuedAccessToken(String token, long expiresInSeconds)`; `expiresInSeconds = 900`.
  - `AccountMemberRepository.findAccountIdByUserId(UUID userId): Optional<UUID>`, `countByAccountId(UUID): long`, `findByAccountId(UUID): List<AccountMember>`.
  - `AccountRepository.findByIdForUpdate(UUID)` (`@Lock(PESSIMISTIC_WRITE)`).
  - Regras do `SecurityFilterChain`: stateless, CSRF desabilitado (API sem cookies de sessão; o cookie de refresh é `SameSite=Strict` e restrito a `/api/v1/auth`). Rotas `permitAll`: `/api/actuator/health`, `/api/v1/public/**`, `/api/v1/auth/register|login|refresh|logout|verify-email|forgot-password|reset-password`, `GET /api/v1/account/invitations/*`, `/v3/api-docs/**`, `/swagger-ui/**` (as duas últimas só fora do perfil `prod`). `/api/v1/admin/**` exige `hasRole("ADMIN")` (convertendo o claim `role` em `ROLE_<role>`). O restante exige autenticação. Um `AuthenticationEntryPoint` e um `AccessDeniedHandler` devolvem ProblemDetail com `UNAUTHENTICATED` e `FORBIDDEN`.
  - Chaves: se `app.auth.jwt-private-key`/`jwt-public-key` estiverem vazios, `JwtKeyConfig` gera um par RSA 2048 efêmero e registra um `WARN`; o perfil `prod` (plano 4) falha no startup se faltarem.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void noToken_returns401Unauthenticated() { /* GET /api/v1/__test/me → 401, $.code UNAUTHENTICATED */ }
@Test void validToken_resolvesCurrentUser() {
    // cria user+account+member diretamente via repositórios; token = tokenService.issueAccessToken(user, accountId)
    // GET com "Authorization: Bearer <token>" → 200, $.userId e $.accountId corretos
}
@Test void staleAccountClaim_isRejected() {
    // token emitido com accountId A; membership do user movida para account B no banco
    // GET /api/v1/__test/me → 401, $.code SESSION_STALE
}
@Test void deletedUser_tokenIsRejected() { /* token válido; delete user; → 401 SESSION_STALE */ }
@Test void expiredToken_isRejected() { /* emitir com MutableClock; avançar 16 min; → 401 */ }
@Test void adminRoute_forbiddenForUser_allowedForAdmin() { /* USER → 403 FORBIDDEN; ADMIN → 200 */ }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=SecurityTest`
Expected: FAIL.

- [ ] **Step 3: Implementar**

`TokenService` usa `NimbusJwtEncoder`, e o decoder é `NimbusJwtDecoder.withPublicKey(...)` com um validador de timestamp que usa o `Clock` injetado. O `CurrentUserArgumentResolver` lê o `Jwt` do `SecurityContext` e consulta `findAccountIdByUserId(sub)`. Se não houver membership, ou se ela for diferente do claim `acc`, lança `ApiException(SESSION_STALE)`. Isso garante que a autoridade do `accountId` seja o banco.

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=SecurityTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): RS256 JWT issuance, resource server and DB-backed current user"
```

---

### Task 5: Infraestrutura de e-mail (Resend + templates i18n)

**Files:**
- Create: `shared/email/EmailSender.java`, `EmailMessage.java`, `LoggingEmailSender.java`, `ResendEmailSender.java`, `EmailTemplateRenderer.java`, `EmailRequestedEvent.java`, `EmailDispatchListener.java`; `shared/config/AsyncConfig.java`
- Create: `templates/email/verify-email.html`, `reset-password.html`, `couple-invitation.html`, `access-code.html`; `i18n/email_pt_BR.properties`, `i18n/email_en.properties`
- Create: `src/test/java/.../support/RecordingEmailSender.java`
- Test: `shared/email/ResendEmailSenderTest.java`, `EmailTemplateRendererTest.java`, `EmailDispatchListenerTest.java`

**Interfaces:**
- Produces:
  - `interface EmailSender { void send(EmailMessage message); }`
  - `record EmailMessage(String to, String subject, String html)`
  - `enum EmailTemplate { VERIFY_EMAIL, RESET_PASSWORD, COUPLE_INVITATION, ACCESS_CODE }`
  - `EmailTemplateRenderer.render(EmailTemplate t, String locale, Map<String,Object> model): RenderedEmail(String subject, String html)`. O assunto vem da chave `<template>.subject` do bundle `i18n/email`.
  - `record EmailRequestedEvent(String to, EmailTemplate template, String locale, Map<String,Object> model)`, publicado pelos services com `ApplicationEventPublisher`.
  - `EmailDispatchListener`: `@Async @TransactionalEventListener(phase = AFTER_COMMIT)`; renderiza, envia e, em caso de exceção, apenas registra `ERROR` em log.
  - `ResendEmailSender`: `POST https://api.resend.com/emails` com `Authorization: Bearer <app.email.resend-api-key>` e corpo `{"from","to":[...],"subject","html"}` via `RestClient`. Escolhido quando `app.email.provider=RESEND`.
  - `LoggingEmailSender`: registra em `INFO` o destinatário, o assunto e os links `http…` extraídos do HTML. Escolhido quando `app.email.provider=LOG`.
  - `RecordingEmailSender` (teste, `@Primary`): `List<EmailMessage> sent()`, `clear()`, `lastTo(String email): EmailMessage`, `extractLink(EmailMessage, String pathPrefix): String`.
  - Modelos dos templates: `VERIFY_EMAIL {name, link}`, `RESET_PASSWORD {name, link}`, `COUPLE_INVITATION {inviterName, accountName, link}`, `ACCESS_CODE {code, link}`.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void resend_postsExpectedPayload() {
    // RestClient.Builder + MockRestServiceServer
    server.expect(requestTo("https://api.resend.com/emails"))
          .andExpect(header("Authorization", "Bearer re_test"))
          .andExpect(jsonPath("$.to[0]").value("ana@mail.com"))
          .andExpect(jsonPath("$.subject").value("Olá"))
          .andRespond(withSuccess("{\"id\":\"1\"}", APPLICATION_JSON));
    sender.send(new EmailMessage("ana@mail.com", "Olá", "<p>x</p>"));
    server.verify();
}
@Test void render_usesLocaleForSubjectAndBody() {
    var pt = renderer.render(VERIFY_EMAIL, "pt-BR", Map.of("name","Ana","link","https://x/verify?token=t"));
    var en = renderer.render(VERIFY_EMAIL, "en",    Map.of("name","Ana","link","https://x/verify?token=t"));
    assertThat(pt.subject()).isEqualTo("Confirme seu e-mail no Duo Finance");
    assertThat(en.subject()).isEqualTo("Confirm your email on Duo Finance");
    assertThat(pt.html()).contains("https://x/verify?token=t").contains("Ana");
}
@Test void event_isSentOnlyAfterCommit() { /* publicar em TransactionTemplate que faz rollback → nada enviado; com commit → 1 e-mail (Awaitility até 2 s) */ }
@Test void senderFailure_doesNotPropagate() { /* EmailSender que lança; commit não falha */ }
```

Os assuntos ficam como nos testes acima. Os demais: reset = "Redefina sua senha" / "Reset your password"; convite = "{0} convidou você para o Duo Finance" / "{0} invited you to Duo Finance"; código = "Seu acesso ao Duo Finance chegou" / "Your Duo Finance access is ready".

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest='ResendEmailSenderTest,EmailTemplateRendererTest,EmailDispatchListenerTest'`
Expected: FAIL.

- [ ] **Step 3: Implementar**

Os templates usam Thymeleaf em HTML simples com estilos inline: logo em texto "Duo Finance" em `#4F46E5`, um botão com o link e o link também em texto puro. `AsyncConfig` declara `@EnableAsync`. Com `spring.threads.virtual.enabled`, o executor padrão já usa virtual threads.

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest='ResendEmailSenderTest,EmailTemplateRendererTest,EmailDispatchListenerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): transactional async email with Resend and localized templates"
```

---

### Task 6: Sessões — refresh token rotativo com detecção de reuso

**Files:**
- Create: `auth/service/AuthSessionService.java`, `auth/controller/RefreshCookies.java`, `auth/controller/AuthController.java` (só `refresh` e `logout` nesta task), `auth/dto/AuthResponse.java`
- Create: `user/dto/MeResponse.java`
- Test: `auth/service/AuthSessionServiceTest.java`, `auth/controller/RefreshLogoutTest.java`

**Interfaces:**
- Consumes: `TokenService.issueAccessToken`, `AccountMemberRepository.findAccountIdByUserId`, `RefreshTokenRepository`.
- Produces:
  - `AuthSessionService.start(User user): SessionTokens` (nova família).
  - `AuthSessionService.refresh(String rawRefreshToken): SessionTokens`.
  - `AuthSessionService.revokeFamilyOf(String rawRefreshToken): void`, `revokeAll(UUID userId): void`.
  - `record SessionTokens(User user, UUID accountId, IssuedAccessToken access, String rawRefreshToken)`.
  - `RefreshCookies.set(HttpServletResponse, String raw)`, `RefreshCookies.clear(HttpServletResponse)`, `RefreshCookies.read(HttpServletRequest): Optional<String>`. Cookie `df_refresh`, `Max-Age` 30 dias, `Secure` conforme `app.auth.cookie-secure`.
  - `record AuthResponse(String accessToken, long expiresIn, MeResponse user)`.
  - `record MeResponse(UUID id, String email, String displayName, String locale, boolean emailVerified, UserRole role, UUID accountId)`.
  - `POST /api/v1/auth/refresh` → `200 AuthResponse` + novo cookie. `POST /api/v1/auth/logout` → `204` + cookie limpo (mesmo sem cookie válido).

Regras de `refresh`:
- Token não encontrado ou expirado → `REFRESH_TOKEN_INVALID`.
- Token já revogado: se `replaced_by_id != null` e a rotação ocorreu há menos de 10 s → `REFRESH_RACE`, sem revogar. Caso contrário, revoga a família inteira → `REFRESH_TOKEN_INVALID`.
- Válido: cria um novo token na mesma família e marca o antigo com `revoked_at` e `replaced_by_id`, tudo sob o lock da linha.
- O `accountId` do novo access token vem do banco (`findAccountIdByUserId`).

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void refresh_rotatesToken() { /* start → refresh(raw1) devolve raw2 ≠ raw1; refresh(raw2) ok */ }
@Test void refresh_reuseAfterGraceWindow_revokesFamily() {
    // start → raw1; refresh(raw1) → raw2; clock.advance(11s); refresh(raw1) → REFRESH_TOKEN_INVALID; refresh(raw2) → REFRESH_TOKEN_INVALID
}
@Test void refresh_reuseWithinGraceWindow_doesNotRevokeFamily() {
    // refresh(raw1) → raw2; clock.advance(3s); refresh(raw1) → REFRESH_RACE; refresh(raw2) → ok
}
@Test void refresh_expiredAfter30Days() { /* clock.advance(30d + 1s) → REFRESH_TOKEN_INVALID */ }
@Test void refresh_usesCurrentMembershipAccountId() { /* mover membership; refresh → accountId novo */ }
// RefreshLogoutTest (MockMvc)
@Test void refreshEndpoint_readsCookieAndSetsRotatedCookie() {
    // cookie df_refresh=raw1 → 200; Set-Cookie contém "df_refresh=", "HttpOnly", "SameSite=Strict", "Path=/api/v1/auth"
}
@Test void logout_revokesFamilyAndClearsCookie() { /* 204; Set-Cookie Max-Age=0; refresh com raw1 → 401 */ }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest='AuthSessionServiceTest,RefreshLogoutTest'`
Expected: FAIL.

- [ ] **Step 3: Implementar** conforme as regras acima, com `@Transactional` em `refresh`.

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest='AuthSessionServiceTest,RefreshLogoutTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): rotating refresh tokens with reuse detection and race grace window"
```

---

### Task 7: Códigos de acesso e cadastro

**Files:**
- Create: `db/migration/V3__beta_access.sql` (`waitlist_entries`, `access_codes`)
- Modify: `db/migration/V2__accounts.sql` → acrescentar `invitations`, `subscriptions`, `categories` (ainda não aplicada em nenhum ambiente, então pode ser editada). `categories` recebe a coluna extra `system_default BOOLEAN NOT NULL DEFAULT false`, marcando as categorias semeadas no cadastro.
- Create: `access/model/AccessCode.java`, `WaitlistEntry.java`, `WaitlistStatus.java`; `access/repository/AccessCodeRepository.java`, `WaitlistEntryRepository.java`; `access/service/AccessCodeService.java`, `RegistrationGate.java`
- Create: `account/model/Invitation.java`, `InvitationStatus.java`; `account/repository/InvitationRepository.java`
- Create: `subscription/model/Subscription.java`, `SubscriptionPlan.java`, `SubscriptionStatus.java`, `PaymentProvider.java`; `subscription/repository/SubscriptionRepository.java`
- Create: `category/model/Category.java`, `CategoryKind.java`; `category/repository/CategoryRepository.java`; `category/service/DefaultCategories.java`, `DefaultCategorySeeder.java`
- Create: `account/service/AccountProvisioningService.java`
- Create: `auth/service/RegistrationService.java`, `auth/dto/RegisterRequest.java`; endpoint `register` no `AuthController`
- Create: `src/test/java/.../support/TestUsers.java`
- Test: `access/service/AccessCodeServiceTest.java`, `account/service/AccountProvisioningServiceTest.java`, `auth/controller/RegisterTest.java`

**Interfaces:**
- Produces:
  - `AccessCodeService.generate(String email /*nullable*/, int maxUses, Instant expiresAt /*nullable*/, String note, UUID createdBy): GeneratedAccessCode`, com `record GeneratedAccessCode(UUID id, String plainCode)`. O formato é de 12 caracteres do alfabeto Crockford base32 (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`) exibidos como `XXXX-XXXX-XXXX`. O hash é `sha256Hex(normalizeCode(code))`, onde `normalizeCode` remove espaços e hífens e converte para maiúsculas.
  - `AccessCodeService.consume(String plainCode, String normalizedEmail): void` lança `ACCESS_CODE_INVALID` se o código não existir, estiver revogado, expirado, esgotado ou vinculado a outro e-mail. Usa lock `PESSIMISTIC_WRITE` e incrementa `used_count`.
  - `AccessCodeService.revoke(UUID id): void`.
  - `RegistrationGate.authorize(RegisterRequest req, String normalizedEmail): RegistrationGrant`, com `sealed interface RegistrationGrant permits OpenGrant, CodeGrant, InvitationGrant`. `InvitationGrant(Invitation invitation)` exige convite `PENDING`, não expirado e com `email` igual. Com `app.registration.mode=OPEN` e sem credencial, devolve `OpenGrant`. Se vier um `invitationToken`, ele tem prioridade sobre o código.
  - `AccountProvisioningService.createSoloAccount(User user): Account` cria a `Account` (nome: pt-BR `"Finanças de {displayName}"`, en `"{displayName}'s finances"`, moeda `EUR`), a membership, a `Subscription(plan=BETA, status=ACTIVE, provider=NONE)` e as categorias padrão no idioma do usuário.
  - `DefaultCategories.forLocale(String locale): List<DefaultCategory>`, com `record DefaultCategory(String name, CategoryKind kind, String color, String icon)`. Despesas: Mercado/Groceries, Moradia/Housing, Transporte/Transport, Restaurantes/Restaurants, Saúde/Health, Lazer/Leisure, Assinaturas/Subscriptions, Educação/Education, Outros/Other. Receitas: Salário/Salary, Freelance/Freelance, Rendimentos/Investment income, Outras receitas/Other income. Os ícones são nomes do Lucide; as cores são tokens hex da paleta definidos na classe.
  - `record RegisterRequest(@NotBlank @Email String email, @NotBlank @Size(min=8,max=128) String password, @NotBlank @Size(max=60) String displayName, @NotNull @Pattern("pt-BR|en") String locale, @AssertTrue boolean acceptTerms, @NotBlank String termsVersion, String accessCode, String invitationToken)`.
  - `POST /api/v1/auth/register` → `201 AuthResponse` + cookie de refresh.
  - `TestUsers` (teste): `registered(String email): TestSession` gera um código via `AccessCodeService`, chama o endpoint e devolve `record TestSession(UUID userId, UUID accountId, String accessToken, String refreshCookie)`; `verified(String email): TestSession` faz o mesmo e preenche `email_verified_at` no banco; `admin(String email): TestSession` promove o usuário a `ADMIN` no banco e emite novo token; `bearer(TestSession)` monta o header.

Ordem do `RegistrationService.register` (tudo em uma transação):
1. Normaliza o e-mail.
2. Aplica `RegistrationGate.authorize` (credencial antes do e-mail).
3. Se o e-mail já existir → `EMAIL_ALREADY_REGISTERED`.
4. Consome o código (se for `CodeGrant`).
5. Cria `User` (`terms_version`, `terms_accepted_at` = agora) e `UserIdentity(LOCAL, email, argon2(password))`.
6. Com `InvitationGrant`, adiciona a membership na `Account` do convite (com lock e verificação de `ACCOUNT_FULL`), marca o convite como `ACCEPTED`, define `email_verified_at` = agora (o link veio do e-mail) e não cria `Account` solo. Caso contrário, chama `createSoloAccount` e `EmailVerificationService.sendVerification(user)`.
7. Chama `AuthSessionService.start(user)`.

Crie nesta task `auth/service/EmailVerificationService.java` apenas com `sendVerification(User user): void`, que gera o `UserToken(EMAIL_VERIFICATION, 24h)` e publica `EmailRequestedEvent(VERIFY_EMAIL)` com link `{publicBaseUrl}/verify-email?token=…`. A Task 9 acrescenta `verify` e `resend`.

- [ ] **Step 1: Escrever os testes que falham**

```java
// AccessCodeServiceTest
@Test void generatedCode_hasDisplayFormat() { assertThat(gen().plainCode()).matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}"); }
@Test void consume_acceptsLowercaseWithoutHyphens() { /* consume(code.toLowerCase().replace("-",""), email) ok */ }
@Test void consume_rejectsExhaustedExpiredRevokedOrOtherEmail() { /* 4 casos → ACCESS_CODE_INVALID */ }
@Test void consume_concurrentSingleUse_onlyOneSucceeds() {
    // 2 threads via ExecutorService + CountDownLatch consumindo o mesmo código maxUses=1 → exatamente 1 sucesso, 1 ACCESS_CODE_INVALID
}
// AccountProvisioningServiceTest
@Test void soloAccount_hasMemberBetaSubscriptionAndLocalizedCategories() {
    // locale pt-BR → 13 categorias system_default=true, inclui "Mercado"; subscription BETA/ACTIVE; 1 membro; currency EUR
}
// RegisterTest
@Test void register_withValidCode_returns201AndSession() { /* $.accessToken presente, $.user.emailVerified false, Set-Cookie df_refresh; 1 e-mail VERIFY_EMAIL */ }
@Test void register_withoutCredentialInInviteOnly_returns400AccessCodeInvalid() {}
@Test void register_openMode_needsNoCode() { /* @TestPropertySource(app.registration.mode=OPEN) numa classe aninhada */ }
@Test void register_normalizesEmail() { /* " Ana@Mail.com" → $.user.email == "ana@mail.com"; código vinculado a "ana@mail.com" aceito */ }
@Test void register_existingEmailWithValidCode_returns409() {}
@Test void register_failureAfterCodeCheck_doesNotConsumeCode() {
    // código maxUses=1; cadastro com e-mail já existente → 409; o mesmo código depois funciona para outro e-mail → 201
}
@Test void register_termsNotAccepted_returns400() { /* acceptTerms=false → VALIDATION_FAILED com errors[].field "acceptTerms" */ }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest='AccessCodeServiceTest,AccountProvisioningServiceTest,RegisterTest'`
Expected: FAIL.

- [ ] **Step 3: Implementar** migrations, entidades, repositórios e services na ordem descrita acima. Use `SecureRandom` para os códigos. O caminho com `InvitationGrant` é coberto pelos testes da Task 11.

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest='AccessCodeServiceTest,AccountProvisioningServiceTest,RegisterTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): access-code gated registration with solo account provisioning"
```

---

### Task 8: Login com bloqueio por tentativas

**Files:**
- Create: `auth/service/LoginService.java`, `auth/dto/LoginRequest.java`; endpoint `login` no `AuthController`
- Test: `auth/controller/LoginTest.java`

**Interfaces:**
- Consumes: `UserIdentityRepository`, `PasswordEncoder`, `AuthSessionService.start`, `Clock`.
- Produces: `record LoginRequest(@NotBlank String email, @NotBlank String password)`; `POST /api/v1/auth/login` → `200 AuthResponse` + cookie.

Regras:
- E-mail inexistente, senha errada ou conta bloqueada respondem sempre `401 INVALID_CREDENTIALS`.
- Para e-mail inexistente, ainda executa `passwordEncoder.matches` contra um hash fixo, igualando o tempo de resposta.
- Cada falha incrementa `failed_login_count`. Na 5ª falha, define `locked_until = now + 15min` e zera o contador.
- Login bem-sucedido zera contador e bloqueio.
- Durante o bloqueio, até a senha correta é recusada.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void login_success_returnsSessionAndResetsCounter() {}
@Test void login_wrongPassword_returnsGeneric401() { /* $.code INVALID_CREDENTIALS */ }
@Test void login_unknownEmail_returnsSameGeneric401() {}
@Test void login_emailIsCaseInsensitive() { /* cadastrado "ana@mail.com", login "ANA@mail.com " → 200 */ }
@Test void login_locksAfterFiveFailuresFor15Minutes() {
    // 5 falhas; 6ª com senha correta → 401; clock.advance(15min + 1s); senha correta → 200
}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest=LoginTest` → FAIL.
- [ ] **Step 3: Implementar** conforme as regras.
- [ ] **Step 4: Rodar e ver passar** — `./mvnw test -Dtest=LoginTest` → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): login with generic errors and temporary lockout"
```

---

### Task 9: Verificação de e-mail e redefinição de senha

**Files:**
- Modify: `auth/service/EmailVerificationService.java` (acrescentar `verify` e `resend`)
- Create: `auth/service/PasswordResetService.java`, `auth/dto/TokenRequest.java`, `ForgotPasswordRequest.java`, `ResetPasswordRequest.java`; endpoints no `AuthController`
- Test: `auth/controller/EmailVerificationTest.java`, `PasswordResetTest.java`

**Interfaces:**
- Produces:
  - `POST /auth/verify-email {token}` → `204`; token inválido, usado ou expirado → `400 TOKEN_INVALID`.
  - `POST /auth/resend-verification` (autenticado) → `204`; invalida tokens anteriores não usados; se já verificado → `204` sem enviar.
  - `POST /auth/forgot-password {email}` → sempre `202`; envia `RESET_PASSWORD` (1 h) só se o e-mail tiver identidade `LOCAL`; invalida resets anteriores não usados.
  - `POST /auth/reset-password {token, newPassword}` → `204`; troca o hash, marca o token como usado, `revokeAll(userId)`, zera contador e bloqueio.
  - Links: `{publicBaseUrl}/verify-email?token=…` e `{publicBaseUrl}/reset-password?token=…`.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void verify_marksEmailVerified() { /* link do RecordingEmailSender → verify → GET /me (Task 10) ou repositório: email_verified_at != null */ }
@Test void verify_tokenIsSingleUse() { /* 2ª vez → 400 TOKEN_INVALID */ }
@Test void verify_expiresAfter24h() {}
@Test void resend_invalidatesPreviousToken() { /* token antigo → 400; novo → 204 */ }
@Test void forgot_unknownEmail_returns202AndSendsNothing() {}
@Test void reset_changesPasswordAndRevokesSessions() {
    // refresh antigo → 401; login com senha antiga → 401; com a nova → 200
}
@Test void reset_expiresAfter1h() {}
@Test void reset_unlocksLockedAccount() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest='EmailVerificationTest,PasswordResetTest'` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): email verification and password reset flows"
```

---

### Task 10: Perfil (`/me`) e dados da `Account`

**Files:**
- Create: `user/service/MeService.java`, `user/controller/MeController.java`, `user/dto/UpdateMeRequest.java`, `ChangePasswordRequest.java`
- Create: `account/service/AccountService.java`, `account/controller/AccountController.java`, `account/dto/AccountResponse.java`, `UpdateAccountRequest.java`, `MemberResponse.java`
- Test: `user/controller/MeControllerTest.java`, `account/controller/AccountControllerTest.java`

**Interfaces:**
- Produces:
  - `GET /api/v1/me` → `MeResponse`. `PATCH /me {displayName?, locale?}` → `MeResponse`.
  - `PUT /me/password {currentPassword, newPassword}` → `200 AuthResponse`. Senha atual errada → `401 INVALID_CREDENTIALS`. Revoga todas as sessões e inicia uma nova, com novo cookie.
  - `GET /api/v1/account` → `record AccountResponse(UUID id, String name, String currency, List<MemberResponse> members, InvitationSummary pendingInvitation /*nullable*/)`, com `record MemberResponse(UUID userId, String displayName, String email, Instant joinedAt)` e `record InvitationSummary(UUID id, String email, Instant expiresAt)`.
  - `PATCH /api/v1/account {name}` (`@NotBlank @Size(max=60)`) → `AccountResponse`.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void getMe_returnsProfile() {}
@Test void patchMe_updatesLocaleAndRejectsUnknownLocale() { /* "es" → 400 VALIDATION_FAILED */ }
@Test void changePassword_wrongCurrent_returns401() {}
@Test void changePassword_revokesOldRefreshAndReturnsNewSession() {}
@Test void getAccount_listsMembersAndCurrency() { /* currency "EUR", 1 membro */ }
@Test void patchAccount_renames() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest='MeControllerTest,AccountControllerTest'` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): profile and account endpoints"
```

---

### Task 11: Convites do casal (criar, cancelar, prévia, aceitar, cadastrar por convite)

**Files:**
- Create: `account/service/InvitationService.java`, `AccountDataProbe.java`, `AccountDeletionService.java`; `account/controller/InvitationController.java`; `account/dto/CreateInvitationRequest.java`, `InvitationPreviewResponse.java`, `AcceptInvitationRequest.java`
- Create: `category/service/CustomCategoryProbe.java`
- Test: `account/controller/InvitationTest.java`

**Interfaces:**
- Consumes: `RegistrationGate`/`InvitationGrant` (Task 7), `AuthSessionService.start`, `AccountRepository.findByIdForUpdate`.
- Produces:
  - `interface AccountDataProbe { boolean hasUserData(UUID accountId); }`. Todas as implementações são injetadas como `List<AccountDataProbe>`. `CustomCategoryProbe` retorna `true` se existir categoria com `system_default = false`. O plano 2 acrescenta probes para contas bancárias, lançamentos, recorrências e orçamentos.
  - `AccountDeletionService.delete(UUID accountId): void` apaga a `Account`; as cascatas do banco removem o resto.
  - `POST /api/v1/account/invitations {email}` → `201 InvitationSummary`. Erros: `EMAIL_NOT_VERIFIED` (quem convida não verificou o e-mail); `ACCOUNT_FULL` (já há 2 membros); `INVITE_PENDING_EXISTS` (já existe convite pendente não expirado); `INVITE_SELF` (convidou o próprio e-mail). Envia `COUPLE_INVITATION` com link `{publicBaseUrl}/invite/{token}` no idioma de quem convida.
  - `DELETE /api/v1/account/invitations/{id}` → `204` (status `CANCELED`); convite de outra `Account` → `404 NOT_FOUND`.
  - `GET /api/v1/account/invitations/{token}` (público) → `record InvitationPreviewResponse(String inviterName, String accountName, String email, boolean existingUser)`. Inválido ou cancelado → `400 INVITE_INVALID`; expirado → `410 INVITE_EXPIRED` (e marca `EXPIRED`).
  - `POST /api/v1/account/invitations/{token}/accept {confirmDiscard: boolean}` (autenticado) → `200 AuthResponse`. Regras, nesta ordem:
    1. Convite válido e não expirado.
    2. E-mail do usuário igual ao do convite; senão `404 NOT_FOUND`, para não revelar o convite.
    3. A `Account` atual do usuário não pode ser a do convite (`INVITE_SELF`) e deve ter 1 membro (`ALREADY_IN_COUPLE`).
    4. Lock na `Account` de destino; com 2 membros → `ACCOUNT_FULL`.
    5. Se algum probe der `true` para a `Account` atual e `confirmDiscard` for falso → `ACCOUNT_HAS_DATA`.
    6. Apaga a `Account` atual, faz `flush`, cria a membership no destino, marca o convite `ACCEPTED`, define `accepted_at` e, se ainda não houver, `email_verified_at`.
    7. Devolve uma nova sessão.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void invite_requiresVerifiedEmail() {}
@Test void invite_sendsLocalizedEmailWithLink() { /* RecordingEmailSender.lastTo("rui@mail.com") contém "/invite/" */ }
@Test void invite_rejectsSecondPendingAndSelf() {}
@Test void preview_isPublicAndShowsInviter() {}
@Test void preview_expiredAfter7Days_returns410() {}
@Test void cancel_otherAccountsInvitation_returns404() {}
@Test void accept_movesUserAndDeletesEmptySoloAccount() {
    // Ana convida Rui (Rui cadastrado separadamente) → Rui aceita → GET /account (token novo de Rui) tem 2 membros; Account solo antiga de Rui não existe mais
}
@Test void accept_matchesInviteEmailIgnoringCase() { /* convite para "Rui@Mail.com", Rui cadastrado como "rui@mail.com" → aceita */ }
@Test void accept_soloAccountWithCustomData_requiresConfirmDiscard() { /* categoria custom → 409 ACCOUNT_HAS_DATA; confirmDiscard=true → 200 */ }
@Test void accept_whileInFullCouple_returns409AlreadyInCouple() {}
@Test void accept_ownInvitation_returns409InviteSelf() {}
@Test void accept_byOtherEmail_returns404() {}
@Test void accept_oldAccessTokenBecomesStale() { /* token antigo de Rui → GET /account 401 SESSION_STALE */ }
@Test void register_withInvitationToken_joinsCoupleWithoutCodeAndIsVerified() {
    // modo INVITE_ONLY; register com invitationToken e o mesmo e-mail → 201; $.user.emailVerified true; $.user.accountId == account da Ana; nenhum e-mail VERIFY_EMAIL
}
@Test void register_withInvitationTokenForOtherEmail_returns400InviteInvalid() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest=InvitationTest` → FAIL.
- [ ] **Step 3: Implementar** conforme as regras, na ordem indicada.
- [ ] **Step 4: Rodar e ver passar** — `./mvnw test -Dtest=InvitationTest` → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): couple invitations with accept, discard confirmation and invite-based signup"
```

---

### Task 12: Sair da `Account` e excluir o usuário (LGPD)

**Files:**
- Create: `account/service/MembershipService.java`; endpoint `POST /account/leave` no `AccountController`
- Modify: `user/service/MeService.java`, `user/controller/MeController.java` (`DELETE /me`); `user/dto/DeleteMeRequest.java`
- Test: `account/controller/LeaveTest.java`, `user/controller/DeleteMeTest.java`

**Interfaces:**
- Produces:
  - `POST /api/v1/account/leave` → `200 AuthResponse`. Com 1 membro → `409 NOT_IN_COUPLE`. Remove a membership, chama `createSoloAccount(user)` e inicia uma nova sessão. Os dados e a assinatura ficam com quem permanece. Convites `PENDING` da `Account` antiga são cancelados.
  - `DELETE /api/v1/me {password}` → `204` + cookie limpo. Senha errada → `401 INVALID_CREDENTIALS`. Se o usuário for o único membro, chama `AccountDeletionService.delete`. Depois apaga o usuário: o banco cascateia identidades e tokens e aplica `SET NULL` em `invited_by` (e, no plano 2, em `created_by`). Convites `PENDING` criados por ele são cancelados.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void leave_soloAccount_returns409() {}
@Test void leave_givesLeaverFreshAccountAndKeepsDataWithPartner() {
    // casal Ana+Rui; Ana renomeia Account para "Ana & Rui"; Rui sai → GET /account (Rui, token novo) tem 1 membro, nome "Finanças de Rui", categorias padrão; Ana vê 1 membro e nome "Ana & Rui"
}
@Test void leave_oldTokenIsStale() {}
@Test void deleteMe_wrongPassword_returns401() {}
@Test void deleteMe_soleMember_deletesAccountEntirely() { /* accounts, subscriptions e categories da Account removidos */ }
@Test void deleteMe_inCouple_keepsAccountForPartner() { /* Ana segue com 1 membro; invitation antiga com invited_by NULL */ }
@Test void deleteMe_revokesSessions() { /* refresh antigo → 401 */ }
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest='LeaveTest,DeleteMeTest'` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): leave couple and LGPD self-deletion"
```

---

### Task 13: Assinatura e `EntitlementService`

**Files:**
- Create: `subscription/service/SubscriptionService.java`, `EntitlementService.java`; `subscription/controller/SubscriptionController.java`, `EntitlementInterceptor.java`; `subscription/dto/SubscriptionResponse.java`
- Modify: `shared/security/WebConfig.java` (registrar o interceptor)
- Create: `shared/security/CurrentUserProvider.java`; Modify: `shared/security/CurrentUserArgumentResolver.java` (passa a delegar ao provider)
- Test: `subscription/EntitlementTest.java` (controllers de teste em `/api/v1/transactions/__probe` e `/api/v1/__test/me`)

**Interfaces:**
- Produces:
  - `EntitlementService.hasAccess(UUID accountId): boolean` (status `TRIALING` ou `ACTIVE`); `EntitlementService.requireAccess(UUID accountId): void` lança `SUBSCRIPTION_INACTIVE`. O plano 2 usa `hasAccess` no job de recorrência.
  - `EntitlementInterceptor`: aplicado aos padrões `/api/v1/bank-accounts/**`, `/api/v1/categories/**`, `/api/v1/transactions/**`, `/api/v1/recurring-rules/**`, `/api/v1/budgets/**` e `/api/v1/dashboard/**`; resolve o `accountId` pela mesma regra do `CurrentUserArgumentResolver` (extraia um `CurrentUserProvider.get(): CurrentUser` compartilhado pelos dois).
  - `GET /api/v1/account/subscription` → `record SubscriptionResponse(SubscriptionPlan plan, SubscriptionStatus status, Instant currentPeriodEnd, boolean hasAccess)`.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void activeBeta_accessesFinancialRoute() { /* GET /api/v1/transactions/__probe → 200 */ }
@Test void inactive_getsPaymentRequiredOnFinancialRoutes() {
    // status = EXPIRED no banco → 402, $.code SUBSCRIPTION_INACTIVE
}
@Test void inactive_canStillUseMeAccountAndSubscription() { /* /me, /account, /account/subscription → 200 */ }
@Test void subscriptionEndpoint_reportsBeta() { /* plan BETA, status ACTIVE, hasAccess true */ }
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest=EntitlementTest` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — `./mvnw test -Dtest=EntitlementTest` → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): per-account subscription model and entitlement gate"
```

---

### Task 14: Lista de espera, rotas públicas e admin

**Files:**
- Create: `access/service/WaitlistService.java`; `access/controller/PublicController.java`, `AdminWaitlistController.java`, `AdminAccessCodeController.java`; `access/dto/JoinWaitlistRequest.java`, `WaitlistEntryResponse.java`, `CreateAccessCodeRequest.java`, `AccessCodeResponse.java`, `CreatedAccessCodeResponse.java`, `RegistrationModeResponse.java`
- Test: `access/controller/PublicWaitlistTest.java`, `AdminAccessTest.java`

**Interfaces:**
- Consumes: `AccessCodeService.generate/revoke`, `EmailRequestedEvent`.
- Produces:
  - `GET /api/v1/public/registration-mode` → `{ "mode": "INVITE_ONLY" }`.
  - `POST /api/v1/public/waitlist {email, locale}` → sempre `202`. Se o e-mail já existir, a chamada é idempotente e não altera nada.
  - `GET /api/v1/admin/waitlist?status=PENDING&page=0&size=20` → página de `record WaitlistEntryResponse(UUID id, String email, String locale, WaitlistStatus status, Instant createdAt, Instant decidedAt)`.
  - `POST /api/v1/admin/waitlist/{id}/approve` → `200 WaitlistEntryResponse`. Gera um código (`email` = da entrada, `maxUses` 1, `expiresAt` = agora + 30 dias, `note` "waitlist"), grava `access_code_id` e envia `ACCESS_CODE` no idioma da entrada com link `{publicBaseUrl}/register?code=<code>`. Se a entrada já estiver `APPROVED`, revoga o código anterior e envia um novo (reenvio). Uma entrada `REJECTED` também pode ser aprovada (o admin mudou de ideia).
  - `POST /api/v1/admin/waitlist/{id}/reject` → `200`.
  - `GET /api/v1/admin/access-codes` → lista de `record AccessCodeResponse(UUID id, String email, int maxUses, int usedCount, Instant expiresAt, Instant revokedAt, String note, Instant createdAt)`. O código em si nunca aparece.
  - `POST /api/v1/admin/access-codes {email?, maxUses (1..1000, default 1), expiresAt?, note?}` → `201 record CreatedAccessCodeResponse(AccessCodeResponse code, String plainCode)`.
  - `POST /api/v1/admin/access-codes/{id}/revoke` → `204`.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void registrationMode_isPublic() {}
@Test void joinWaitlist_isIdempotentAndCaseInsensitive() { /* "Ana@x.com" e "ana@x.com" → 1 entrada, ambos 202 */ }
@Test void admin_approve_emailsCodeThatRegisters() {
    // join → admin approve → RecordingEmailSender: link "/register?code=" → register com esse código e o mesmo e-mail → 201
}
@Test void admin_approveTwice_revokesPreviousCode() { /* código antigo → ACCESS_CODE_INVALID */ }
@Test void admin_createCode_returnsPlainCodeOnce() { /* POST → plainCode presente; GET lista sem plainCode */ }
@Test void admin_revokedCode_cannotRegister() {}
@Test void adminRoutes_forbiddenForRegularUser() { /* 403 FORBIDDEN em GET /admin/waitlist e POST /admin/access-codes */ }
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest='PublicWaitlistTest,AdminAccessTest'` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Rodar a suíte completa e o Spotless**

Run: `./mvnw spotless:apply && ./mvnw verify`
Expected: `BUILD SUCCESS`, todos os testes verdes.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(backend): public waitlist and admin access-code management"
```
