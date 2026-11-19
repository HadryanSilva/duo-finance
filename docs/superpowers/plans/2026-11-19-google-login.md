# Login com Google — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a pessoa entra ou se cadastra com um clique em "Continuar com Google", sem abrir o beta fechado, e quem só tem Google consegue usar todas as configurações.

**Architecture:**
- O frontend carrega o Google Identity Services e envia o *ID token* para `POST /api/v1/auth/google`.
- O backend valida o token e então faz uma de três coisas: entra (achou a identidade pelo `sub`), vincula por e-mail, ou devolve um *ticket de cadastro*. O ticket é um JWT nosso, com `aud = google-signup` e validade de 15 min, que `POST /api/v1/auth/google/complete` troca por um usuário novo, passando pelo `RegistrationGate`.
- As sessões são as mesmas de hoje: `AuthSessionService.start` e o cookie `df_refresh`.

**Tech Stack:**
- Backend: Spring Boot 4 / Java 25, Spring Security OAuth2 Resource Server (Nimbus), JPA, Flyway, JUnit 5 + Testcontainers + MockMvcTester.
- Frontend: Vue 3 + Pinia + TanStack Query + vee-validate/zod, Vitest + MSW.
- Infra: nginx e bats.

**Spec:** `docs/superpowers/specs/2026-11-19-google-login-design.md`. Leia o spec antes de qualquer task: as regras de vínculo, pré-sequestro e conflito estão lá.

## Global Constraints

- **Branch:** `feature/google-login`, que já existe e já tem o commit do spec. O PR vai contra a **`develop`**.
- **Comandos:** todos com prefixo `rtk` (por exemplo `rtk git commit`, `rtk npm run test`). Backend: `cd backend && ./mvnw ...`. Frontend: `cd frontend && npm ...`.
- **Banco:** não há migration de schema. `user_identities` já aceita `GOOGLE`, e `password_hash` é nulo.
- **Configuração:** `app.auth.google-client-id`, vindo da env `GOOGLE_CLIENT_ID`. Vazio desliga a funcionalidade: os endpoints respondem `404 GOOGLE_LOGIN_DISABLED` e o botão não aparece.
- **Validação do ID token do Google:**
  - JWKS em `https://www.googleapis.com/oauth2/v3/certs`;
  - `iss` ∈ {`https://accounts.google.com`, `accounts.google.com`};
  - `aud` contém o client ID;
  - `exp` no futuro;
  - `email_verified = true`.
- **Ticket:** TTL de **15 min**, `aud = ["google-signup"]`. Access tokens não têm `aud`. O decoder das access tokens rejeita qualquer token com `aud`, e o do ticket exige `google-signup`.
- **Códigos de erro novos** (nunca renomear os existentes):
  - `GOOGLE_TOKEN_INVALID` (401)
  - `GOOGLE_EMAIL_UNVERIFIED` (401)
  - `GOOGLE_SIGNUP_TICKET_INVALID` (401)
  - `GOOGLE_ACCOUNT_CONFLICT` (409)
  - `GOOGLE_LOGIN_DISABLED` (404)
- **Identidades:** no máximo **uma** identidade `GOOGLE` por usuário. A identidade `LOCAL` sempre tem `provider_subject = users.email` (normalizado).
- **i18n:** todo texto ou código de erro novo existe em `pt-BR` e `en`.
- **Tipos da API no frontend:** `frontend/src/shared/api/schema.ts` é gerado (`npm run api:types` com o backend em perfil `dev` rodando), e o job de CI `api-types` confere. Nunca edite à mão; regenere e re-exporte em `types.ts`.
- **CSP:** só os caminhos `/gsi/` do Google, nunca o domínio inteiro.

## Review Focus

1. **Corrida: dois cliques no "Criar conta" ou duas abas com o mesmo ticket.** O segundo `complete` deve dar `409 EMAIL_ALREADY_REGISTERED`, sem 500 nem usuário duplicado. A tela trata o 409 refazendo o login com Google, ou mandando para `/login` se não houver credential. *Teste na Task 4 (`complete_twice_secondIs409`) e na Task 7.*
2. **E-mail do Google com maiúsculas** (`Ana@Gmail.com`) e conta local `ana@gmail.com`. Deve vincular, não pedir cadastro. *Teste na Task 3 (`google_linksCaseInsensitiveEmail`).*
3. **Recarregar `/register/google`** ou abrir a rota direto, sem estado. Deve redirecionar para `/login` sem quebrar. *Teste na Task 7.*
4. **Usuário só com Google tenta "Trocar senha" pela API.** Hoje a resposta é `INVALID_CREDENTIALS`, e deve continuar assim, sem 500. *Teste na Task 5 (`changePassword_googleOnly_isInvalidCredentials`).*
5. **Ticket expirado no meio do formulário** (o relógio passa de 15 min). Deve dar `401 GOOGLE_SIGNUP_TICKET_INVALID`, e a tela pede para clicar no Google de novo. *Testes na Task 2 (`ticket_expired_rejected`) e na Task 7.*

---

### Task 1: Configuração, verificador do ID token do Google e config pública

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/shared/config/AppProperties.java`. Em `Auth`, adicionar `String googleClientId`.
- Modify: `backend/src/main/resources/application-prod.yml`. Adicionar `google-client-id: ${GOOGLE_CLIENT_ID:}` em `app.auth`.
- Create: `backend/src/main/java/br/dev/duofinance/auth/google/GoogleTokenDecoder.java`
- Create: `backend/src/main/java/br/dev/duofinance/auth/google/GoogleIdTokenVerifier.java`
- Create: `backend/src/main/java/br/dev/duofinance/auth/google/GoogleIdentity.java`
- Modify: `backend/src/main/java/br/dev/duofinance/shared/error/ErrorCode.java`. Adicionar os 5 códigos.
- Modify: `backend/src/main/java/br/dev/duofinance/access/dto/RegistrationModeResponse.java` e `access/controller/PublicController.java`
- Create: `backend/src/test/java/br/dev/duofinance/support/TestGoogleTokens.java`
- Modify: `backend/src/test/java/br/dev/duofinance/IntegrationTest.java`. Importar `TestGoogleTokens`.
- Modify: `backend/src/test/resources/application-test.yml`. Adicionar `app.auth.google-client-id: test-client-id`.
- Test: `backend/src/test/java/br/dev/duofinance/auth/google/GoogleIdTokenVerifierTest.java` (unitário) e o teste existente do `PublicController`, ou um novo `PublicAuthConfigTest` se não houver.

**Interfaces:**
- Produces:
  - `public interface GoogleTokenDecoder { Jwt decode(String idToken); }`. Lança `JwtException` para assinatura ou formato inválidos. O bean de produção, `GoogleJwksTokenDecoder`, é um `@Component` package-private que embrulha `NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build()`, com validador de timestamp usando o `Clock` da aplicação.
  - Ele **não** pode ser um bean do tipo `JwtDecoder`, porque o Resource Server usaria o bean errado.
  - `public record GoogleIdentity(String subject, String email, String name)`. O `email` já vem normalizado com `SecureTokens.normalizeEmail`, e `name` pode ser nulo.
  - `@Service GoogleIdTokenVerifier`:
    - `boolean enabled()` diz se o client ID está configurado;
    - `GoogleIdentity verify(String idToken)` lança `ApiException`. Ordem: desligado → `GOOGLE_LOGIN_DISABLED`; falha de decode, `iss` fora da lista ou `aud` sem o client ID → `GOOGLE_TOKEN_INVALID`; `email_verified` diferente de `true` → `GOOGLE_EMAIL_UNVERIFIED`.
  - `RegistrationModeResponse(RegistrationMode mode, String googleClientId)`. O `googleClientId` é nulo quando a funcionalidade está desligada.
  - Teste: `@TestConfiguration TestGoogleTokens`:
    - expõe um `@Primary GoogleTokenDecoder` que valida com uma chave RSA gerada localmente;
    - tem `String idToken(String sub, String email, boolean emailVerified)`, que emite com `iss = https://accounts.google.com`, `aud = test-client-id`, `name = "Ana Google"` e `exp = clock + 5 min`;
    - tem `String idToken(Map<String,Object> overrides)` para os casos inválidos.

- [ ] **Step 1: Escrever os testes que falham**

`GoogleIdTokenVerifierTest` usa um `GoogleTokenDecoder` falso que devolve um `Jwt` montado com `Jwt.withTokenValue("t")...`, client ID `cid`. Casos:

```java
@Test void verify_validToken_returnsNormalizedIdentity()   // email "Ana@Mail.com" → identity.email() == "ana@mail.com", subject == "g-1"
@Test void verify_issWithoutScheme_accepted()              // iss "accounts.google.com"
@Test void verify_wrongIssuer_isTokenInvalid()             // iss "https://evil.example" → ApiException code GOOGLE_TOKEN_INVALID
@Test void verify_wrongAudience_isTokenInvalid()           // aud ["other"] → GOOGLE_TOKEN_INVALID
@Test void verify_decoderThrows_isTokenInvalid()           // decoder lança BadJwtException → GOOGLE_TOKEN_INVALID
@Test void verify_emailNotVerified_isEmailUnverified()     // email_verified false → GOOGLE_EMAIL_UNVERIFIED
@Test void verify_noClientId_isDisabled()                  // googleClientId null → GOOGLE_LOGIN_DISABLED; enabled() == false
```

Integração: `GET /api/v1/public/registration-mode` → `$.googleClientId == "test-client-id"`.

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && rtk ./mvnw test -Dtest='GoogleIdTokenVerifierTest,PublicAuthConfigTest'`
Expected: FAIL (compilação: as classes não existem).

- [ ] **Step 3: Implementar** as interfaces acima, os 5 `ErrorCode` e o campo novo na resposta pública.

- [ ] **Step 4: Rodar e ver passar**

Run: o mesmo comando, e depois `rtk ./mvnw test` inteiro, porque `IntegrationTest` mudou.
Expected: PASS.

- [ ] **Step 5: Commit**

`rtk git commit -m "feat(auth): verify Google ID tokens and expose the client ID publicly"`

---

### Task 2: Ticket de cadastro, separado das access tokens

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/auth/google/SignupTicketService.java`
- Modify: `backend/src/main/java/br/dev/duofinance/shared/security/JwtKeyConfig.java`. No `jwtDecoder`, compor o validador de timestamp com um validador que **rejeita qualquer token com `aud` não vazio**.
- Test: `backend/src/test/java/br/dev/duofinance/auth/google/SignupTicketTest.java` (`@IntegrationTest`)

**Interfaces:**
- Consumes: `GoogleIdentity` (Task 1), o bean `KeyPair jwtKeyPair`, `JwtEncoder` e `Clock`.
- Produces:
  - `@Service SignupTicketService`:
    - `String issue(GoogleIdentity identity)` emite claims `sub` = Google subject, `email`, `name`, `aud = ["google-signup"]`, `iat` e `exp = now + 15 min`;
    - `GoogleIdentity read(String ticket)` lança `ApiException(GOOGLE_SIGNUP_TICKET_INVALID)` em qualquer falha;
    - `static final Duration TTL = Duration.ofMinutes(15)`.
  - O decoder do ticket é um `NimbusJwtDecoder` construído dentro do serviço, a partir da chave pública. Não é bean. Ele exige `aud` contendo `google-signup` e valida timestamps com o `Clock`.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void ticket_roundTrips()                      // read(issue(identity)) equals identity
@Test void ticket_expired_rejected()                // clock.advance(Duration.ofMinutes(16)) → GOOGLE_SIGNUP_TICKET_INVALID
@Test void ticket_tampered_rejected()               // muda um caractere da assinatura → GOOGLE_SIGNUP_TICKET_INVALID
@Test void ticket_asBearer_isUnauthenticated()      // GET /api/v1/me com Authorization: Bearer <ticket> → 401
@Test void accessToken_asTicket_rejected()          // read(testUsers.registered(...).accessToken()) → GOOGLE_SIGNUP_TICKET_INVALID
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && rtk ./mvnw test -Dtest=SignupTicketTest`
Expected: FAIL.

- [ ] **Step 3: Implementar** `SignupTicketService` e o validador de `aud` no `jwtDecoder`.

- [ ] **Step 4: Rodar e ver passar**

Run: `rtk ./mvnw test`. A suíte inteira prova que as access tokens atuais continuam aceitas.
Expected: PASS.

- [ ] **Step 5: Commit**

`rtk git commit -m "feat(auth): signup ticket that access-token decoding rejects"`

---

### Task 3: `POST /api/v1/auth/google`: entrar, vincular ou pedir cadastro

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/auth/google/GoogleAuthService.java`
- Create: `backend/src/main/java/br/dev/duofinance/auth/dto/GoogleLoginRequest.java` (`@NotBlank String credential`)
- Create: `backend/src/main/java/br/dev/duofinance/auth/dto/GoogleLoginResponse.java`
- Modify: `backend/src/main/java/br/dev/duofinance/auth/controller/AuthController.java`
- Modify: `backend/src/main/java/br/dev/duofinance/shared/security/SecurityConfig.java`. Adicionar `/api/v1/auth/google` e `/api/v1/auth/google/complete` a `PUBLIC_PATHS`.
- Modify: `backend/src/main/java/br/dev/duofinance/user/repository/UserIdentityRepository.java`. Adicionar `void deleteByUserIdAndProvider(UUID userId, IdentityProvider provider)`.
- Modify: `backend/src/main/java/br/dev/duofinance/auth/repository/UserTokenRepository.java`. Adicionar `@Modifying int deleteUnusedByUserId(UUID userId)`, que apaga os tokens com `used_at is null`.
- Test: `backend/src/test/java/br/dev/duofinance/auth/controller/GoogleLoginTest.java`

**Interfaces:**
- Consumes: `GoogleIdTokenVerifier.verify`, `SignupTicketService.issue`, `AuthSessionService.start` e `revokeAll`.
- Produces:
  - `GoogleAuthService.authenticate(String credential)`, `@Transactional`, devolve `GoogleAuthResult`. É um `sealed interface` com dois casos:
    - `Authenticated(SessionTokens session)`;
    - `SignupRequired(String signupTicket, String email, String displayName)`. O `displayName` é o `name` do Google, ou a parte local do e-mail se o nome vier nulo.
  - `GoogleLoginResponse(String status, String accessToken, Long expiresIn, MeResponse user, String signupTicket, String email, String displayName)`. O `status` é `"AUTHENTICATED"` ou `"SIGNUP_REQUIRED"`, e os campos do outro caso vêm nulos. Fábricas: `authenticated(SessionTokens)` e `signupRequired(SignupRequired)`.
  - Controller: `POST /api/v1/auth/google` → `200 GoogleLoginResponse`. Só seta o cookie de refresh no caso `AUTHENTICATED`.
- Lógica, em ordem:
  1. Procura `(GOOGLE, sub)`. Se achar, carrega o usuário.
  2. Se não achar, busca o usuário por `users.findByEmail(email)` com lock (`findByIdForUpdate`).
     - Se o usuário já tem uma identidade `GOOGLE` (de outro `sub`): `GOOGLE_ACCOUNT_CONFLICT`.
     - Se não, salva `UserIdentity(user, GOOGLE, sub, null, now)`.
     - Se `emailVerifiedAt == null`, aplica o pré-sequestro: `deleteByUserIdAndProvider(LOCAL)`, `deleteUnusedByUserId`, `revokeAll`. Depois preenche `emailVerifiedAt = now` e `updatedAt`.
  3. Se não achou ninguém: `SignupRequired`.
  4. Em `Authenticated`: `sessions.start(user)`.

- [ ] **Step 1: Escrever os testes que falham.** Os tokens vêm de `TestGoogleTokens.idToken`.

```java
@Test void google_unknownUser_returnsSignupRequired()        // status SIGNUP_REQUIRED, signupTicket != null, email "novo@mail.com", displayName "Ana Google", sem Set-Cookie
@Test void google_linkedSubject_authenticates()              // depois de um vínculo, outro e-mail no token com mesmo sub → AUTHENTICATED com o mesmo user.id
@Test void google_verifiedLocalUser_linksAndKeepsPassword()  // testUsers.verified("ana@mail.com"); google → AUTHENTICATED, Set-Cookie df_refresh=; login com TestUsers.PASSWORD continua 200
@Test void google_unverifiedLocalUser_dropsPasswordAndSessions() // testUsers.registered(...); google → AUTHENTICATED; login com senha → 401; POST /auth/refresh com o cookie antigo → 401; user.emailVerifiedAt != null; nenhum user_token sem uso
@Test void google_linksCaseInsensitiveEmail()                 // conta "ana@gmail.com", token com "Ana@Gmail.com" → AUTHENTICATED
@Test void google_secondGoogleAccountSameEmail_isConflict()   // vincula sub g-1; token com sub g-2 e mesmo e-mail → 409 GOOGLE_ACCOUNT_CONFLICT
@Test void google_unverifiedEmail_is401()                     // email_verified false → 401 GOOGLE_EMAIL_UNVERIFIED
@Test void google_wrongAudience_is401()                       // overrides aud "other" → 401 GOOGLE_TOKEN_INVALID
@Test void google_lockedPasswordAccount_stillAuthenticates()  // 5 logins errados (locked_until no futuro) → google AUTHENTICATED
```

O teste de funcionalidade desligada é unitário e já está coberto na Task 1 (`verify_noClientId_isDisabled`). Não é preciso subir um contexto novo.

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && rtk ./mvnw test -Dtest=GoogleLoginTest`
Expected: FAIL (404).

- [ ] **Step 3: Implementar** o serviço, os DTOs, o endpoint, os paths públicos e os métodos novos dos repositórios.

- [ ] **Step 4: Rodar e ver passar**

Run: `rtk ./mvnw test -Dtest=GoogleLoginTest`
Expected: PASS.

- [ ] **Step 5: Commit**

`rtk git commit -m "feat(auth): Google sign-in with e-mail linking and pre-hijack protection"`

---

### Task 4: `POST /api/v1/auth/google/complete`: cadastro com ticket

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/access/service/RegistrationGate.java`. A assinatura passa a `authorize(String accessCode, String invitationToken, String normalizedEmail)`. O chamador antigo passa `req.accessCode()` e `req.invitationToken()`.
- Modify: `backend/src/main/java/br/dev/duofinance/auth/service/RegistrationService.java`. Extrair o miolo comum.
- Create: `backend/src/main/java/br/dev/duofinance/auth/dto/GoogleCompleteRequest.java`
- Modify: `AuthController.java`
- Test: `backend/src/test/java/br/dev/duofinance/auth/controller/GoogleSignupTest.java`. Também rodar `RegisterTest` e `RegisterInvitationRaceTest`, que já existem.

**Interfaces:**
- Consumes: `SignupTicketService.read` e o `RegistrationGate`.
- Produces:
  - `GoogleCompleteRequest(@NotBlank String signupTicket, @NotBlank @Size(max = 60) String displayName, @NotNull @Pattern(regexp = "pt-BR|en") String locale, @AssertTrue boolean acceptTerms, @NotBlank @Size(max = 50) String termsVersion, String accessCode, String invitationToken)`.
  - `RegistrationService.registerWithGoogle(GoogleCompleteRequest req)`, que devolve `SessionTokens`.
- Comportamento:
  - Mesma ordem do `register`: credencial, unicidade de e-mail, consumo do código.
  - O `User` é criado com o e-mail do ticket e com `emailVerifiedAt = now`.
  - A identidade é `UserIdentity(GOOGLE, ticket.subject, null)`.
  - Com convite: `joinInvitedAccount`. Sem convite: `createSoloAccount`, **sem** `sendVerification`.
  - A violação de unicidade em `user_identities_provider_subject_key` também vira `EMAIL_ALREADY_REGISTERED`.
  - Controller: `POST /api/v1/auth/google/complete` → `201 AuthResponse` com o cookie de refresh. Desligado → `404 GOOGLE_LOGIN_DISABLED`, verificado com `GoogleIdTokenVerifier.enabled()`.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void complete_withAccessCode_createsVerifiedUserWithoutVerificationEmail() // 201; me.emailVerified true; emails.sentTo("novo@mail.com") vazio; nenhuma identidade LOCAL
@Test void complete_withoutCredential_inviteOnly_is400AccessCodeInvalid()
@Test void complete_withInvitation_joinsInviterAccount()                          // accountId == conta de quem convidou; me.onboarded true
@Test void complete_invitationForOtherEmail_isInviteInvalid()                     // 400 INVITE_INVALID
@Test void complete_expiredTicket_is401()                                         // clock.advance(16 min) → GOOGLE_SIGNUP_TICKET_INVALID
@Test void complete_twice_secondIs409()                                           // mesmo ticket duas vezes → 201, depois 409 EMAIL_ALREADY_REGISTERED
@Test void complete_thenGoogleLogin_authenticates()                               // POST /auth/google com o mesmo sub → AUTHENTICATED
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && rtk ./mvnw test -Dtest=GoogleSignupTest`
Expected: FAIL.

- [ ] **Step 3: Implementar** a refatoração do gate, `registerWithGoogle` e o endpoint.

- [ ] **Step 4: Rodar e ver passar**

Run: `rtk ./mvnw test -Dtest='GoogleSignupTest,RegisterTest,RegisterInvitationRaceTest,PublicWaitlistTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

`rtk git commit -m "feat(auth): complete Google sign-up through the registration gate"`

---

### Task 5: Usuários sem senha: `me`, exclusão e reset criando `LOCAL`

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/user/dto/MeResponse.java`. Adicionar `boolean hasPassword` e `List<String> providers`.
- Modify: todos os pontos que chamam `MeResponse.of` (`AuthResponse`, `MeService` e outros: `rg "MeResponse.of"`).
- Modify: `backend/src/main/java/br/dev/duofinance/user/dto/DeleteMeRequest.java`
- Modify: `backend/src/main/java/br/dev/duofinance/user/service/MeService.java`
- Modify: `backend/src/main/java/br/dev/duofinance/auth/service/PasswordResetService.java`
- Test: `backend/src/test/java/br/dev/duofinance/user/controller/PasswordlessUserTest.java`. Também rodar os testes de `Me`, `PasswordReset` e `Leave` que já existem.

**Interfaces:**
- Produces:
  - `MeResponse.of(User user, UUID accountId, List<UserIdentity> identities)`:
    - `hasPassword` diz se existe `LOCAL` com hash não nulo;
    - `providers` tem os nomes dos providers em ordem alfabética, por exemplo `["GOOGLE","LOCAL"]`.
  - Para não espalhar consultas, adicionar `List<UserIdentity> findByUserId(UUID userId)` ao `UserIdentityRepository`. Quem monta a resposta busca as identidades.
  - `DeleteMeRequest(String password, String confirmEmail)`, com os dois campos opcionais no bean validation.
  - `MeService.delete(CurrentUser, DeleteMeRequest)`:
    - se o usuário tem senha, exige `password` correta;
    - se não tem, exige que `normalizeEmail(confirmEmail)` seja igual a `user.getEmail()`;
    - em qualquer falha, `INVALID_CREDENTIALS`.
  - `PasswordResetService`:
    - `requestReset` busca por `users.findByEmail(normalizeEmail(raw))` e continua em silêncio para e-mails desconhecidos;
    - `reset` cria `UserIdentity(LOCAL, user.getEmail(), hash)` quando ela não existe.

- [ ] **Step 1: Escrever os testes que falham.** Os usuários Google são criados pelo fluxo da Task 4, com um helper `testUsers.google(String email)` adicionado a `TestUsers`.

```java
@Test void me_googleOnly_hasNoPassword()                  // hasPassword false, providers ["GOOGLE"]
@Test void me_linked_hasBoth()                            // verified + google → hasPassword true, providers ["GOOGLE","LOCAL"]
@Test void delete_googleOnly_withMatchingEmail_deletes()  // DELETE /api/v1/me {"confirmEmail":"Novo@Mail.com"} → 204
@Test void delete_googleOnly_withWrongEmail_is401()       // INVALID_CREDENTIALS
@Test void delete_withPassword_stillRequiresPassword()    // confirmEmail certo, sem senha → 401
@Test void changePassword_googleOnly_isInvalidCredentials()
@Test void reset_googleOnly_createsLocalIdentity()        // forgot-password → link no e-mail → reset → login com a nova senha 200
@Test void forgot_unknownEmail_staysSilent()              // 202 e nenhum e-mail
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && rtk ./mvnw test -Dtest=PasswordlessUserTest`
Expected: FAIL.

- [ ] **Step 3: Implementar.**

- [ ] **Step 4: Rodar e ver passar**

Run: `rtk ./mvnw test`, com a suíte inteira e `spotless:check` se o build exigir (`rtk ./mvnw verify`).
Expected: PASS.

- [ ] **Step 5: Commit**

`rtk git commit -m "feat(user): support password-less Google users in me, deletion and password reset"`

---

### Task 6: Frontend: tipos, API e botão do Google no login e no cadastro

**Files:**
- Regenerate: `frontend/src/shared/api/schema.ts`. Rodar `npm run api:types` com o backend `dev` no ar e re-exportar os tipos novos em `frontend/src/shared/api/types.ts`: `GoogleLoginRequest`, `GoogleLoginResponse`, `GoogleCompleteRequest`, e os campos novos de `MeResponse` e `RegistrationModeResponse`.
- Modify: `frontend/src/features/auth/api/auth-api.ts`
- Modify: `frontend/src/shared/api/queries/public.ts`
- Create: `frontend/src/features/auth/google/load-gsi.ts`
- Create: `frontend/src/features/auth/google/GoogleButton.vue`
- Create: `frontend/src/features/auth/google/google-signup-store.ts`
- Modify: `frontend/src/features/auth/pages/LoginPage.vue` e `RegisterPage.vue`
- Modify: `frontend/src/shared/i18n/locales/pt-BR.json` e `en.json`
- Test: `frontend/src/features/auth/google/GoogleButton.spec.ts`, `LoginPage.spec.ts` e `RegisterPage.spec.ts`

**Interfaces:**
- Produces:
  - `auth-api.ts`:
    - `googleLogin(http, credential: string)` → `Promise<GoogleLoginResponse>`;
    - `googleComplete(http, body: GoogleCompleteRequest)` → `Promise<AuthResponse>`.
  - `public.ts`: `useAuthConfig()` → query `{ mode, googleClientId }`, com a mesma chave e o mesmo endpoint de `useRegistrationMode`. `useRegistrationMode` passa a derivar dela, sem mudar a API.
  - `load-gsi.ts`: `loadGsi(): Promise<typeof google.accounts.id>`. Injeta `<script src="https://accounts.google.com/gsi/client" async>` uma única vez (promessa memorizada) e declara os tipos mínimos de `window.google` num `.d.ts` local.
  - `GoogleButton.vue`:
    - props `{ clientId: string; invitationToken?: string }`;
    - inicializa com `ux_mode: 'popup'` e `locale` igual ao idioma atual do i18n;
    - no callback, chama `googleLogin`;
    - `AUTHENTICATED`: `session.applyAuth(resp)` e emite `authenticated`;
    - `SIGNUP_REQUIRED`: grava no `google-signup-store` e faz `router.push('/register/google')`;
    - erros viram toast com `t('errors.<code>')`.
  - `google-signup-store.ts` (Pinia, só em memória): estado `{ ticket, email, displayName, invitationToken, credential } | null`, com `set(...)` e `clear()`. O `credential` serve para refazer o login quando o `complete` der 409.
  - Login e Cadastro: se `googleClientId` existir, o `GoogleButton` fica acima do formulário, com o separador `t('auth.or')`. No `authenticated`, o login segue o mesmo destino de hoje (`safeRedirect(route.query.redirect)`).

- [ ] **Step 1: Escrever os testes que falham.** Fazer stub de `loadGsi` com `vi.mock`: um `renderButton` falso que expõe o callback.

```ts
it('não mostra o botão quando googleClientId é nulo')
it('AUTHENTICATED aplica a sessão e redireciona como o login por senha')
it('SIGNUP_REQUIRED guarda o ticket e vai para /register/google')
it('GOOGLE_ACCOUNT_CONFLICT mostra a mensagem traduzida')
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd frontend && rtk vitest run src/features/auth`
Expected: FAIL.

- [ ] **Step 3: Implementar** o que está acima, mais as chaves i18n (`auth.or`, `auth.continueWithGoogle` e `errors.GOOGLE_*`).

- [ ] **Step 4: Rodar e ver passar**

Run: `rtk vitest run && rtk npm run lint && rtk vue-tsc -b`
Expected: PASS.

- [ ] **Step 5: Commit**

`rtk git commit -m "feat(web): Continue with Google on login and register"`

---

### Task 7: Frontend: "Concluir cadastro" e convite

**Files:**
- Create: `frontend/src/features/auth/pages/GoogleSignupPage.vue` e `GoogleSignupPage.spec.ts`
- Modify: `frontend/src/app/router.ts`. Rota `/register/google`, com `layout: 'public'` e o mesmo guard de visitante de `/register`.
- Modify: `frontend/src/features/auth/pages/AcceptInvitePage.vue` e o spec
- Modify: `frontend/src/features/auth/schemas.ts`. Schema zod do formulário.
- Modify: os arquivos de i18n

**Interfaces:**
- Consumes: `google-signup-store`, `googleComplete`, `googleLogin`, `useRegistrationMode`, `invitationPreview`, `TERMS_VERSION`.
- Comportamento de `GoogleSignupPage`:
  - Sem estado na store: `router.replace('/login')`.
  - Mostra o e-mail (só leitura), o nome (pré-preenchido e editável, máximo 60), o código de acesso e o aceite dos termos e da privacidade (com os links de `/terms` e `/privacy`).
  - O código fica **oculto** quando há `invitationToken` válido para este e-mail, ou quando o modo é `OPEN`.
  - Se há `invitationToken`, busca o `invitationPreview`. Se o e-mail do convite for diferente do e-mail do Google, mostra o aviso `t('auth.google.inviteOtherEmail', { email })`, descarta o `invitationToken` e mostra o campo de código.
  - No envio, `googleComplete({ signupTicket, displayName, locale, acceptTerms, termsVersion: TERMS_VERSION, accessCode?, invitationToken? })`, depois `session.applyAuth`, `store.clear()` e navegação para `/onboarding` ou `/dashboard` pelo mesmo critério do `RegisterPage` atual.
  - `401 GOOGLE_SIGNUP_TICKET_INVALID`: mostra `t('auth.google.ticketExpired')`, limpa a store e mostra o `GoogleButton` de novo.
  - `409 EMAIL_ALREADY_REGISTERED`: chama `googleLogin(store.credential)`. Se der `AUTHENTICATED`, aplica a sessão e segue. Senão, manda para `/login`.
  - Os demais códigos vão para os erros de campo ou de formulário existentes (`ACCESS_CODE_INVALID`, `INVITE_*`).
- `AcceptInvitePage`, para visitante: o `GoogleButton` recebe `invitationToken`, que vai para a store no `SIGNUP_REQUIRED`. No `AUTHENTICATED`, a página fica onde está e passa ao estado de usuário logado que já existe (aceite com `confirmDiscard`).

- [ ] **Step 1: Escrever os testes que falham**

```ts
it('sem estado redireciona para /login')
it('INVITE_ONLY sem convite exige código e envia accessCode')
it('com convite para o mesmo e-mail oculta o código e envia invitationToken')
it('convite para outro e-mail avisa e mostra o campo de código')
it('ticket expirado mostra aviso e o botão do Google')
it('409 refaz o login com Google e segue autenticado')
it('AcceptInvitePage: Google AUTHENTICATED mostra o aceite do convite')
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd frontend && rtk vitest run src/features/auth`
Expected: FAIL.

- [ ] **Step 3: Implementar.**

- [ ] **Step 4: Rodar e ver passar**

Run: `rtk vitest run && rtk npm run lint && rtk vue-tsc -b`
Expected: PASS.

- [ ] **Step 5: Commit**

`rtk git commit -m "feat(web): finish Google sign-up page and Google on the invite page"`

---

### Task 8: Frontend: configurações para quem não tem senha

**Files:**
- Modify: os componentes de senha, exclusão e perfil em `frontend/src/features/settings/components/`. Localize com `rg -l "password|delete" frontend/src/features/settings`.
- Modify: `frontend/src/features/settings/pages/SettingsPage.spec.ts`
- Modify: os arquivos de i18n

**Interfaces:**
- Consumes: `me.hasPassword` e `me.providers` (Task 5, via tipos regenerados na Task 6).
- Comportamento:
  - **Trocar senha:** com `hasPassword`, a seção fica como hoje. Sem senha, mostra `t('settings.password.googleOnly')` e um link para `/forgot-password`, com texto como "crie uma senha pelo 'Esqueci minha senha'".
  - **Excluir conta:** com `hasPassword`, pede a senha, como hoje. Sem senha, pede o campo "Digite seu e-mail para confirmar" e envia `{ confirmEmail }`.
  - **Perfil:** mostra a linha "Entra com", com `providers` traduzidos (`GOOGLE` → "Google", `LOCAL` → "E-mail e senha").

- [ ] **Step 1: Escrever os testes que falham**

```ts
it('sem senha: esconde trocar senha e mostra o aviso com link')
it('sem senha: exclusão envia confirmEmail')
it('com senha: exclusão envia password (regressão)')
it('perfil lista os métodos de entrada')
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd frontend && rtk vitest run src/features/settings`
Expected: FAIL.

- [ ] **Step 3: Implementar.**

- [ ] **Step 4: Rodar e ver passar**

Run: `rtk vitest run && rtk npm run lint`
Expected: PASS.

- [ ] **Step 5: Commit**

`rtk git commit -m "feat(web): settings for password-less Google users"`

---

### Task 9: CSP, env, runbook e política de privacidade

**Files:**
- Modify: `infra/nginx/snippets/security-headers.conf`. Na CSP:
  - `script-src 'self' https://accounts.google.com/gsi/client`
  - `style-src 'self' https://accounts.google.com/gsi/style`
  - `connect-src 'self' https://accounts.google.com/gsi/`
  - `frame-src https://accounts.google.com/gsi/`

  O resto fica idêntico. Atualizar o comentário do topo dizendo por que o Google entra.
- Modify: `infra/tests/nginx.bats`. A variável `CSP` (linha 15) fica igual à nova política.
- Modify: `infra/.env.example` e `infra/.env.local.example`. Adicionar `GOOGLE_CLIENT_ID=` com um comentário curto.
- Modify: `docs/runbook.md`. Seção "Login com Google":
  - criar o OAuth Client "Web application" no Google Cloud Console;
  - *Authorized JavaScript origins* = domínio de produção e `http://localhost:5173`;
  - tela de consentimento com o nome do app e os links de `/terms` e `/privacy`;
  - preencher `GOOGLE_CLIENT_ID` e reiniciar o backend;
  - com o valor vazio, o botão some.
- Modify: `frontend/src/features/landing/pages/PrivacyPage.vue` e as chaves i18n dela. Nova seção sobre login com Google:
  - dados recebidos: nome, e-mail e identificador da conta Google;
  - finalidade: autenticação;
  - base legal: execução do contrato (art. 6(1)(b) GDPR);
  - o Google como provedor terceiro, com link para a política de privacidade do Google.

  Em pt-BR e en. O texto final é revisado pelo usuário no PR.

- [ ] **Step 1: Atualizar a CSP no teste primeiro**

Run: `rtk bats infra/tests/nginx.bats`. Se o ambiente não tiver bats ou Docker, registre e confie no job `infra` do CI.
Expected: FAIL (a CSP ainda é a antiga).

- [ ] **Step 2: Atualizar `security-headers.conf`**

Run: `rtk bats infra/tests/nginx.bats`
Expected: PASS.

- [ ] **Step 3: Escrever env, runbook e política de privacidade.** Rodar `cd frontend && rtk vitest run src/features/landing`.
Expected: PASS. Se houver snapshot ou teste de chaves i18n, ele precisa continuar verde.

- [ ] **Step 4: Verificação manual no navegador** (skill `run`):
  - subir o backend `dev` com `GOOGLE_CLIENT_ID` real e o frontend no Vite;
  - conferir que o botão renderiza sem violação de CSP no console;
  - testar o caminho completo: cadastro novo com código, login de volta e vínculo com conta local.

  Se não houver client ID disponível, avise o usuário e deixe isso para a revisão do PR.

- [ ] **Step 5: Commit**

`rtk git commit -m "chore(infra): allow Google Identity Services in CSP; document GOOGLE_CLIENT_ID and Google sign-in privacy"`

---

## Encerramento

Ao terminar todas as tasks:
- rodar `cd backend && rtk ./mvnw verify` e `cd frontend && rtk npm run test && rtk npm run lint && rtk npm run build`;
- fazer push de `feature/google-login`;
- abrir o PR contra a **`develop`**, citando o spec e o plano e listando o que o usuário precisa fazer no Google Cloud Console.
