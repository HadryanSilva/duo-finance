# Login com Google — design

**Data:** 2026-11-19 · **Tamanho:** projeto (endpoints de auth + ajustes em `me` + telas novas + CSP + política de privacidade)

## Intenção e porquê

Reduzir o atrito no cadastro e no login. Hoje a pessoa precisa criar uma senha, confirmar o e-mail e lembrar da senha depois. Com "Continuar com Google", ela entra com um clique, e o e-mail já chega verificado.

O spec do MVP (`2026-11-05-duo-finance-mvp-design.md`) deixou o login social fora do escopo, mas preparou o modelo: `user_identities` com `provider` (`LOCAL | GOOGLE | APPLE`), unicidade em `(provider, provider_subject)` e emissão de tokens num `AuthSessionService`/`TokenService` independente do login local.

## Decisões tomadas com o usuário

- **O Google vem primeiro (opção B).** Um único botão "Continuar com Google" aparece no login, no cadastro e na página de convite. Se a identidade Google já pertence a um usuário, ou se o e-mail já é de um usuário, a pessoa entra direto. Se não, ela cai numa tela curta de **Concluir cadastro**, com o nome e o e-mail vindos do Google. Ali ela informa o código de acesso (a menos que tenha vindo de um convite ou que o modo seja `OPEN`) e aceita os termos.
  - Alternativa rejeitada **A**, o código antes do Google: quem clica no botão sem conta dá com a cara na porta, que é exatamente o atrito que se quer tirar.
- **Integração pela opção 1:** Google Identity Services (GIS) no frontend e verificação do *ID token* no backend.
  - Rejeitada **2**, fluxo OAuth2 com redirect via `spring-security-oauth2-client`: exige client secret, estado de sessão HTTP num backend stateless e um vai-e-volta entre domínios.
  - Rejeitada **3**, Authorization Code + PKCE com troca no backend: só precisamos da identidade, não de APIs do Google, então seria complexidade sem retorno.

## Propostas minhas, apresentadas ao usuário e não contestadas

- **Vínculo automático por e-mail.** Se o Google afirma `email_verified = true` e o e-mail normalizado já existe em `users`, a `UserIdentity(GOOGLE)` é criada para esse usuário e ele entra.
- **Conta nova via Google nasce com e-mail verificado.** Não há e-mail de verificação.
- **A senha passa a ser opcional.** Quem só tem Google confirma a exclusão da conta digitando o próprio e-mail.

## Objetivos

1. Entrar com Google numa conta existente, seja pelo vínculo por `sub` ou pelo vínculo automático por e-mail.
2. Cadastrar com Google respeitando o beta fechado (código ou convite), com aceite dos termos.
3. Cadastrar com Google a partir de um link de convite, entrando direto na `Account` do parceiro.
4. Usuários sem senha conseguem usar todas as telas de configurações, incluindo excluir a conta.
5. A funcionalidade fica desligada quando o client ID do Google não está configurado: o botão some e os endpoints respondem `404`.

## Não-objetivos

- Login com Apple ou outros provedores, embora o desenho não deva atrapalhá-los.
- Desvincular o Google ou gerenciar provedores nas configurações. Só mostramos quais métodos a pessoa usa.
- Chamar APIs do Google em nome do usuário (Drive, Calendar etc.).
- Atualizar `users.email` quando o e-mail da conta Google muda. A identidade é ligada pelo `sub`, e o e-mail do Duo Finance não muda.
- One Tap e login automático. Só o botão.

## Anti-objetivos (o que tornaria isso um fracasso, mesmo funcionando)

- Abrir o beta: cadastrar via Google sem código, convite ou modo `OPEN`.
- Permitir tomar a conta de outra pessoa via vínculo por e-mail (ver "Pré-sequestro" abaixo).
- Fazer o ticket de cadastro funcionar como access token, ou o contrário.
- Afrouxar a CSP além do mínimo que o GIS exige.

## Restrições

- Spring Boot 4 / Java 25. JWT RS256 próprio, cookie de refresh e `AuthSessionService.start(user)` existentes.
- **CSP** (`infra/nginx/snippets/security-headers.conf`) hoje é `script-src 'self'`, `connect-src 'self'`, sem `frame-src`. O GIS precisa de:
  - `script-src https://accounts.google.com/gsi/client`
  - `frame-src https://accounts.google.com/gsi/`
  - `connect-src https://accounts.google.com/gsi/`
  - `style-src https://accounts.google.com/gsi/style`

  Só esses caminhos, nada de domínio inteiro. Os testes em `infra/tests` que conferem os headers precisam ser atualizados.
- **Política de privacidade** (`PrivacyPage.vue`, pt-BR e en): segue GDPR e direito do consumidor da UE. Precisa citar o Google como provedor de login, os dados recebidos (nome, e-mail, identificador da conta Google) e a finalidade (autenticação).
- O fluxo de branches é a partir de `develop`, com PR para `develop`.

## Desenho

### Configuração

- `app.auth.google-client-id` (env `GOOGLE_CLIENT_ID`), opcional. Vazio desliga a funcionalidade.
- O frontend descobre o client ID por um endpoint público: estende o que já existe em `PublicController` (hoje `/api/v1/public/registration-mode`) ou cria `/api/v1/public/auth-config`, à escolha do builder. Se o client ID vier nulo, nenhum botão aparece. Assim o build do frontend não depende de variável de ambiente.

### Verificação do ID token (backend)

- Um `GoogleIdTokenVerifier` próprio, sobre um `NimbusJwtDecoder` com JWKS `https://www.googleapis.com/oauth2/v3/certs`. Valida:
  - assinatura;
  - `iss` ∈ {`https://accounts.google.com`, `accounts.google.com`};
  - `aud` = client ID;
  - `exp`;
  - `email_verified = true`.

  Devolve `sub`, `email` normalizado e `name`. Token inválido gera `401 GOOGLE_TOKEN_INVALID`. E-mail não verificado gera `401 GOOGLE_EMAIL_UNVERIFIED`.
- Esse decoder é um bean separado do `JwtDecoder` das access tokens, para não interferir no Resource Server.
- Nos testes, o verificador é substituível: um decoder com chave RSA local assina ID tokens falsos. Nada vai à rede.

### `POST /api/v1/auth/google` — `{ credential }`

1. Verifica o ID token.
2. Se existe `UserIdentity(GOOGLE, sub)`, carrega o usuário.
3. Se não existe, mas há usuário com esse e-mail, **vincula**:
   - cria `UserIdentity(GOOGLE, sub, password_hash = null)`;
   - se `email_verified_at` era nulo, preenche agora.
   - **No máximo uma identidade Google por usuário** (decisão minha). Se o usuário já tem uma `GOOGLE` com outro `sub`, não vincula e responde `409 GOOGLE_ACCOUNT_CONFLICT`. Isso fecha o caso de um e-mail reatribuído, por exemplo num domínio Workspace, abrir a conta de outra pessoa.
   - **Pré-sequestro:** se o e-mail **não** estava verificado no momento do vínculo, a identidade `LOCAL` existente é removida e todas as sessões do usuário são revogadas. Os `user_tokens` pendentes (verificação e reset) também são apagados. Motivo: alguém pode ter cadastrado o e-mail da vítima com uma senha que conhece; quando a dona de verdade entra com Google, essa senha não pode continuar dando acesso. Se o e-mail já estava verificado, a senha fica.
     - *Decisão minha:* os dados da `Account` são mantidos, e não há e-mail nem aviso. O caso é raro, e a dona pode apagar o que não reconhecer ou excluir a conta.
4. Se encontrou ou vinculou o usuário, `sessions.start(user)`, seta o cookie de refresh e responde `200 { status: "AUTHENTICATED", ...AuthResponse }`.
5. Se não há usuário, responde `200 { status: "SIGNUP_REQUIRED", signupTicket, email, displayName }`.

Esse fluxo não passa pelo bloqueio por tentativas de senha (`failed_login_count`/`locked_until`), que protege só o login por senha.

### Ticket de cadastro

- É um JWT assinado com a nossa chave RS256, válido por **15 min**. Claims: `sub` do Google, `email`, `name` e uma marca de propósito: `aud = "google-signup"` e `typ` próprio.
- O `JwtDecoder` das access tokens **precisa rejeitar** o ticket, e o validador do ticket precisa rejeitar access tokens. Por exemplo: exigir `aud` específico em ambos, ou uma claim de tipo. Um teste cobre cada sentido.
- Fica sem estado no servidor: não há tabela de cadastros pendentes. Reuso dentro dos 15 min só é possível até o primeiro cadastro dar certo, porque depois disso `(GOOGLE, sub)` e `users.email` são únicos.

### `POST /api/v1/auth/google/complete`

Corpo: `{ signupTicket, displayName, locale, acceptTerms, termsVersion, accessCode?, invitationToken? }`

1. Valida o ticket.
2. Passa pelo `RegistrationGate`. Hoje ele recebe um `RegisterRequest`; deve passar a receber os campos de credencial (`accessCode`, `invitationToken`) para servir aos dois cadastros. Mesma prioridade (convite antes de código) e mesmos erros. O e-mail comparado com o convite é o do Google: se o convite foi para outro e-mail, a resposta é `INVITE_INVALID`.
3. Mesmo corpo do `RegistrationService.register`, refatorado para compartilhar a lógica:
   - checa unicidade do e-mail;
   - consome o código;
   - cria o `User` com `email_verified_at = now`;
   - cria a `UserIdentity(GOOGLE, sub)` sem `LOCAL`;
   - entra na `Account` do convite, ou cria a `Account` solo **sem** enviar e-mail de verificação.
4. Responde `201 AuthResponse` com o cookie de refresh.
5. Corrida: se o mesmo `sub` ou e-mail já foi cadastrado, a violação de unicidade vira `409 EMAIL_ALREADY_REGISTERED`, como no cadastro atual. O frontend então refaz o `POST /auth/google`, e a pessoa entra.

### Usuários sem senha

- `MeResponse` ganha `hasPassword: boolean` (existe `LOCAL` com hash) e `providers: string[]`.
- **Trocar senha:** a seção só aparece com `hasPassword`. Quem não tem senha vê "Você entra com Google". Para criar uma senha, usa "Esqueci minha senha", que passa a **criar** a identidade `LOCAL` quando ela não existe. Hoje o `PasswordResetService` fica em silêncio para quem não tem identidade `LOCAL`. Isso muda:
  - O `requestReset` passa a procurar o usuário por `users.email`. Continua em silêncio para e-mails desconhecidos, então a proteção contra enumeração não muda.
  - Na redefinição, a identidade é criada com `provider_subject = users.email`, para que o `LoginService` continue achando por `(LOCAL, email)`.
  - Isso vale também para quem passou pelo vínculo de pré-sequestro. O link vai para a caixa de entrada verificada, então só a dona de verdade consegue usá-lo.
- **Excluir conta:** `DeleteMeRequest` passa a ter `password` opcional e `confirmEmail` opcional. Com senha, a senha é exigida, como hoje. Sem senha, `confirmEmail` precisa ser igual ao e-mail do usuário (após normalização). Senão, `INVALID_CREDENTIALS`.

### Frontend

- **Componente `GoogleButton`:** carrega `https://accounts.google.com/gsi/client` sob demanda, só nas telas que o usam, e renderiza o botão oficial com o idioma da interface. Ao receber `credential`, chama `POST /auth/google`.
- **Login, Cadastro e Aceitar convite** (`LoginPage`, `RegisterPage`, `AcceptInvitePage`): o botão fica acima do formulário, com um separador "ou". No convite, o `invitationToken` vai junto para a tela de conclusão.
- **Convite com quem já tem conta** (decisão minha): se `/auth/google` responde `AUTHENTICATED` na `AcceptInvitePage`, a página segue exatamente como hoje para um usuário logado. Ou seja, mostra o aceite com `InvitationService.accept`, incluindo `confirmDiscard` e os erros `ACCOUNT_HAS_DATA`, `ALREADY_IN_COUPLE` e `INVITE_SELF`. Ela não pula para o dashboard.
- **Convite para outro e-mail** (decisão minha): se o e-mail do Google difere do e-mail do convite (que já vem no preview do convite), a tela "Concluir cadastro" avisa logo de cara: "Este convite foi enviado para x@…". Ela não manda o `invitationToken` e mostra o campo de código. A pessoa pode voltar e escolher outra conta Google.
- **Nova rota `/register/google`, "Concluir cadastro":**
  - o ticket e os dados do Google passam por estado em memória, não pela URL;
  - se a página é recarregada sem ticket, volta para `/login`;
  - mostra o e-mail (só leitura), o nome (editável) e o código de acesso (oculto se veio de convite ou se o modo é `OPEN`), mais o aceite dos termos;
  - se o ticket expirou (`401 GOOGLE_SIGNUP_TICKET_INVALID`), avisa e pede para clicar no botão de novo.

  ```
  ┌──────────────────────────────────┐
  │  Concluir cadastro               │
  │  ana@gmail.com                   │
  │  Nome   [ Ana Souza          ]   │
  │  Código [ ____________       ]   │
  │  [x] Aceito os termos e a        │
  │      política de privacidade     │
  │  [        Criar conta        ]   │
  └──────────────────────────────────┘
  ```
- **Configurações:** as seções de senha e exclusão seguem `hasPassword` como descrito acima. O perfil mostra "Entra com: Google / E-mail e senha".
- **i18n:** pt-BR e en para todos os textos e códigos de erro novos.

### Códigos de erro novos

- `GOOGLE_TOKEN_INVALID` (401)
- `GOOGLE_EMAIL_UNVERIFIED` (401)
- `GOOGLE_SIGNUP_TICKET_INVALID` (401)
- `GOOGLE_ACCOUNT_CONFLICT` (409, o usuário já tem outra conta Google vinculada)
- `GOOGLE_LOGIN_DISABLED` (404, quando não há client ID)

### Banco

Não há migration de schema: `user_identities` já aceita `GOOGLE` e `password_hash` nulo. O builder deve conferir se algum código assume que todo usuário tem `LOCAL`; os pontos conhecidos são `MeService.changePassword`/`delete`, `LoginService` e `PasswordResetService`.

### Operação

- Criar o OAuth Client ("Web application") no Google Cloud Console, com *Authorized JavaScript origins* = domínio de produção e `http://localhost:<porta do dev>`. Configurar a tela de consentimento com o nome do app e os links de termos e privacidade.
- Adicionar `GOOGLE_CLIENT_ID` no `.env` de produção e no `docs/runbook.md`.

## Testes

- **Backend, integração com Testcontainers e verificador de Google falso:**
  - login por `sub`;
  - vínculo por e-mail verificado (a senha continua valendo);
  - vínculo com e-mail não verificado (a senha some e as sessões são revogadas);
  - `email_verified = false` é rejeitado;
  - token com `aud` errado é rejeitado;
  - `SIGNUP_REQUIRED`;
  - concluir com código, com convite e em modo `OPEN`;
  - convite para outro e-mail dá `INVITE_INVALID`;
  - ticket expirado ou ticket usado como access token é rejeitado, e access token usado como ticket também;
  - corrida dá 409;
  - funcionalidade desligada dá 404;
  - excluir conta sem senha com `confirmEmail`;
  - reset de senha cria `LOCAL`.
- **Frontend (Vitest + MSW):**
  - o botão aparece ou não conforme a config;
  - os dois desfechos de `/auth/google`;
  - "Concluir cadastro" com e sem convite, com ticket expirado e com reload sem estado;
  - configurações com `hasPassword` falso.
- **Infra:** testes de header atualizados para a nova CSP.

## O que fica com o builder

- Nome e formato exato do endpoint de config pública.
- Organização interna das classes (por exemplo `GoogleAuthService`, ou onde mora o verificador), seguindo os pacotes por funcionalidade.
- Como carregar o script do GIS e tipar o `window.google`.
- Texto exato das mensagens e da seção nova da política de privacidade, revisado com o usuário no PR.
