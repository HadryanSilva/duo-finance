# Backend — Domínio Financeiro — Implementation Plan (2 de 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar a API financeira do casal — contas bancárias com saldo calculado, categorias, lançamentos (receita, despesa, transferência) com filtros e paginação, regras recorrentes com geração automática, orçamentos por categoria e o dashboard mensal — sobre a fundação do plano 1.

**Architecture:** Mesmo monolito Spring MVC do plano 1, com pacotes novos `bankaccount`, `transaction`, `recurring`, `budget`, `dashboard` e a expansão de `category`. Todo acesso é filtrado pelo `accountId` de `CurrentUser`; referências a IDs de outra `Account` respondem `404`. Saldos e progresso de orçamento são sempre calculados em SQL agregado, nunca armazenados. A recorrência usa uma única função de calendário (`RecurrenceSchedule`) compartilhada pela geração, pelo job diário e pelas "próximas recorrências" do dashboard.

**Tech Stack:** o do plano 1 (Java 25, Spring Boot 4.1.2, Hibernate 7, Flyway, PostgreSQL 16, Testcontainers, MockMvcTester), mais Spring Data JPA Specifications e `@Scheduled`.

**Spec:** `docs/superpowers/specs/2026-11-05-duo-finance-mvp-design.md` (seções 2 — Finanças e Regras de negócio —, 3 — API e Organização — e 6).

**Sequência de planos:** (1) fundação e identidade — PR #1; (2) este; (3) frontend; (4) infra e deploy. Este branch (`feature/backend-finance`) parte do branch do PR #1.

## Global Constraints

- Tudo do plano 1 continua valendo (pacote `br.dev.duofinance`, UUID v7, `Clock` injetado, `ProblemDetail` com `code`, `404` para outra `Account`, `402 SUBSCRIPTION_INACTIVE` nas rotas financeiras, `ON DELETE CASCADE` de `accounts`, autor `ON DELETE SET NULL`).
- As migrations V1–V3 estão congeladas: todo schema novo vai em `V4__finance.sql`.
- Valores monetários em `BIGINT` de centavos (`*_cents`), na moeda da `Account` (EUR). Em requests: `amountCents`/`monthlyLimitCents` entre `1` e `100_000_000_000`; `initialBalanceCents` entre `-100_000_000_000` e `100_000_000_000`.
- Datas de lançamento são `LocalDate` (`DATE`), aceitas entre `1970-01-01` e `2100-12-31`. "Hoje" é sempre `LocalDate.now(clock)` com o `Clock` UTC.
- Saldo de conta bancária: `initial_balance_cents + Σ INCOME + Σ TRANSFER recebidas − Σ EXPENSE − Σ TRANSFER enviadas`, considerando só lançamentos com `date <= hoje`.
- `INCOME`/`EXPENSE`: `categoryId` obrigatório e do mesmo `kind`; `bankAccountId` opcional; `destinationBankAccountId` nulo. `TRANSFER`: `categoryId` nulo; origem e destino obrigatórios e diferentes. Transferências não entram em receitas, despesas, gastos por categoria nem orçamentos.
- Categoria ou conta bancária arquivada não pode ser usada em lançamento ou regra **nova**, nem trocada para ela numa edição; um lançamento que já a usa pode ser editado mantendo-a.
- Atualizações usam `PUT` com o recurso completo (sem semântica de campo nulo = "não alterar").
- Strings: `name` de categoria e conta bancária `1..60` após `trim`; `description` opcional `0..140` após `trim` (vazia vira `null`); `color` `^#[0-9A-Fa-f]{6}$`; `icon` `^[a-z0-9-]{1,40}$` (nome Lucide).
- Listas paginadas devolvem `record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)`; `page` padrão `0`, `size` padrão `50`, limitado a `1..100` (como no plano 1).
- Novos `ErrorCode` (adicionar só os da sua task, com estes status): `CATEGORY_ARCHIVED 409`, `BANK_ACCOUNT_ARCHIVED 409`, `CATEGORY_KIND_MISMATCH 400`, `TRANSFER_INVALID 400`, `CATEGORY_NAME_TAKEN 409`, `CATEGORY_IN_USE 409`, `BANK_ACCOUNT_IN_USE 409`, `IN_USE_BY_ACTIVE_RULE 409`, `RECURRING_DATE_TAKEN 409`, `BUDGET_EXISTS 409`, `BUDGET_CATEGORY_INVALID 400`.
- Recorrência: job `@Scheduled(cron = "0 15 0 * * *", zone = "UTC")`; ocorrência `k` = `startDate` mais `k` períodos calculado a partir de `startDate` (nunca encadeado), de modo que `MONTHLY` no dia 31 cai no último dia dos meses curtos e volta ao 31 depois.
- Orçamento: estado `NORMAL` (< 80%), `WARNING` (≥ 80%), `EXCEEDED` (≥ 100%); `percent = floor(spent × 100 / limit)`.

## Review Focus

1. **Excluir a conta do único membro com dados financeiros**: o cascade de `accounts` precisa apagar lançamentos, regras, orçamentos, categorias e contas bancárias no mesmo `DELETE`. Por isso as FKs de `transactions`/`recurring_rules` para `categories`/`bank_accounts` são `NO ACTION` (checadas no fim do comando), nunca `RESTRICT`. Teste em Task 1 (`accountDelete_cascadesThroughFinanceData`) e Task 10 (`deleteMe_soleMemberWithFinanceData_returns204`).
2. **Referência a recurso de outra `Account`** em um request (`categoryId`, `bankAccountId`, `holderUserId`, filtro `categoryId`): responde `404 NOT_FOUND`, nunca grava nem vaza dados. Teste em Task 4 (`create_withOtherAccountsCategory_returns404`) e na suíte de isolamento da Task 10.
3. **Mensal no dia 31 e anual em 29/02**: 31/01 → 28/02 (29 em bissexto) → 31/03; 29/02/2028 → 28/02/2029 → 29/02/2032. Teste em Task 6 (`monthlyOn31st_clampsAndRecovers`, `yearlyOnLeapDay_clamps`).
4. **Job roda duas vezes no mesmo dia, ou concorre com a criação da regra**: nenhuma ocorrência duplicada (chave única `(recurring_rule_id, date)` + `ON CONFLICT DO NOTHING`). Teste em Task 7 (`job_runTwice_isIdempotent`).
5. **Busca `q` com `%`, `_` ou maiúsculas**: busca literal e case-insensitive na descrição; `"50%"` não casa com tudo. Teste em Task 5 (`list_qIsLiteralAndCaseInsensitive`).

---

## File Structure

```
backend/src/main/resources/db/migration/V4__finance.sql
backend/src/main/java/br/dev/duofinance/
├── shared/web/            PageResponse, PageRequests (clamp page/size)
├── shared/finance/        FinanceValidation (limites de valor, data, strings)
├── bankaccount/           model(BankAccount, BankAccountType) · repository(BankAccountRepository, BankAccountBalanceQuery) ·
│                          service(BankAccountService, BankAccountDataProbe) · controller(BankAccountController) · dto
├── category/              (existe) + service(CategoryService) · controller(CategoryController) · dto
├── transaction/           model(Transaction, TransactionKind) · repository(TransactionRepository, TransactionSpecifications) ·
│                          service(TransactionService, TransactionReferenceValidator, TransactionQueryService, TransactionDataProbe) ·
│                          controller(TransactionController) · dto
├── recurring/             model(RecurringRule, Frequency) · repository(RecurringRuleRepository) ·
│                          service(RecurrenceSchedule, RecurringRuleService, RecurringGenerator, RecurringGenerationJob,
│                          RecurringRuleDataProbe, SchedulingConfig) · controller(RecurringRuleController) · dto
├── budget/                model(Budget) · repository(BudgetRepository) · service(BudgetService, BudgetProgressQuery, BudgetDataProbe) ·
│                          controller(BudgetController) · dto
└── dashboard/             service(DashboardService) · controller(DashboardController) · dto
backend/src/test/java/br/dev/duofinance/support/FinanceFixtures.java   (cria contas, categorias, lançamentos via API)
```

Testes seguem o padrão do plano 1: `@IntegrationTest` + `MockMvcTester`, `TestUsers` para sessões, `MutableClock` (reiniciado a cada teste, truncado em µs) para "hoje".

---

### Task 1: Schema financeiro e ajustes herdados do plano 1

**Files:**
- Create: `db/migration/V4__finance.sql`
- Create: `shared/web/PageResponse.java`, `shared/web/PageRequests.java`, `shared/finance/FinanceValidation.java`
- Modify: `shared/security/CurrentUserProvider.java` (cache por requisição), `account/service/InvitationService.java` + `InvitationRepository.java` (`cancel` condicional)
- Test: `db/FinanceSchemaTest.java`, `shared/security/CurrentUserProviderTest.java`, `account/controller/InvitationTest.java` (novo caso)

**Interfaces:**
- Produces:
  - Tabelas (colunas da spec, seção 2 — Finanças): `bank_accounts`, `transactions`, `recurring_rules`, `budgets`.
    - Toda tabela: `account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE`, `created_at`/`updated_at timestamptz NOT NULL`, índice em `account_id`.
    - `created_by` e `holder_user_id` → `users(id) ON DELETE SET NULL`, com índice.
    - `transactions.category_id`/`bank_account_id`/`destination_bank_account_id` e as mesmas colunas de `recurring_rules` → FK **sem** `ON DELETE` (= `NO ACTION`).
    - `transactions.recurring_rule_id` → `recurring_rules(id) ON DELETE SET NULL`; `CREATE UNIQUE INDEX uq_transactions_rule_date ON transactions (recurring_rule_id, date) WHERE recurring_rule_id IS NOT NULL`.
    - `budgets.category_id` → `categories(id) ON DELETE CASCADE`, `UNIQUE`.
    - `CHECK`: `amount_cents > 0`, `monthly_limit_cents > 0`, `kind IN (...)`, `type IN (...)`, `frequency IN ('WEEKLY','MONTHLY','YEARLY')`, `end_date IS NULL OR end_date >= start_date`, e a forma de transferência: `(kind = 'TRANSFER' AND category_id IS NULL AND bank_account_id IS NOT NULL AND destination_bank_account_id IS NOT NULL AND bank_account_id <> destination_bank_account_id) OR (kind <> 'TRANSFER' AND category_id IS NOT NULL AND destination_bank_account_id IS NULL)` em `transactions` e `recurring_rules`.
    - Índices de consulta: `transactions (account_id, date DESC)`, `transactions (bank_account_id)`, `transactions (destination_bank_account_id)`, `transactions (category_id)`, `recurring_rules (active, next_run_date)`.
    - Troca o índice de nome de categoria para case-insensitive: `DROP INDEX uq_categories_active_name; CREATE UNIQUE INDEX uq_categories_active_name ON categories (account_id, kind, lower(name)) WHERE archived_at IS NULL;`
  - `record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)` com `static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper)`.
  - `PageRequests.of(int page, int size, Sort sort): PageRequest` (clampa `page >= 0`, `size 1..100`).
  - `FinanceValidation`: constantes `MAX_AMOUNT_CENTS = 100_000_000_000L`, `MIN_DATE = 1970-01-01`, `MAX_DATE = 2100-12-31`; `static String normalizeDescription(String)` (trim, vazio → `null`).
  - `CurrentUserProvider.get()` guarda o `CurrentUser` resolvido num atributo da requisição (`CurrentUserProvider.class.getName()`) e o reutiliza, mantendo o comportamento atual (`SESSION_STALE` etc.).
  - `InvitationRepository.cancelPending(UUID id, UUID accountId): int` (`UPDATE ... SET status = CANCELED WHERE id = :id AND account_id = :accountId AND status = 'PENDING'`); `InvitationService.cancel` passa a usá-lo e responde `404` quando `0`.

- [ ] **Step 1: Escrever os testes que falham**

```java
// FinanceSchemaTest (JdbcTemplate sobre o schema real)
@Test void transferShapeIsEnforcedByCheck() { /* INSERT TRANSFER com category_id → DataIntegrityViolationException */ }
@Test void ruleOccurrenceIsUniquePerDate() { /* 2 INSERTs (rule, 2026-01-01) → segundo viola uq_transactions_rule_date */ }
@Test void accountDelete_cascadesThroughFinanceData() {
    // conta + categoria + bank_account + recurring_rule + transaction (com categoria e conta) + budget;
    // DELETE FROM accounts WHERE id = ? → sucesso; contagem 0 em todas as tabelas para esse account_id
}
@Test void userDelete_setsCreatedByAndHolderToNull() {}
@Test void ruleDelete_detachesGeneratedTransactions() { /* recurring_rule_id vira NULL */ }
@Test void categoryNameUniquenessIgnoresCase() { /* "Mercado" e "mercado" ativos, mesmo kind → violação */ }
// CurrentUserProviderTest
@Test void resolvesOncePerRequest() { /* duas chamadas na mesma requisição → 1 consulta a findAccountIdByUserId (spy) */ }
// InvitationTest
@Test void cancel_afterAccept_doesNotRewriteAcceptedInvitation() { /* convite aceito → DELETE → 404; status segue ACCEPTED */ }
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest='FinanceSchemaTest,CurrentUserProviderTest,InvitationTest'` → FAIL.
- [ ] **Step 3: Implementar** a migration, os utilitários e os dois ajustes.
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS; depois `./mvnw test` completo.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): finance schema, page response and inherited plan-1 fixes"
```

---

### Task 2: Contas bancárias com saldo calculado

**Files:**
- Create: `bankaccount/model/BankAccount.java`, `BankAccountType.java`; `bankaccount/repository/BankAccountRepository.java`, `BankAccountBalanceQuery.java`; `bankaccount/service/BankAccountService.java`, `BankAccountDataProbe.java`; `bankaccount/controller/BankAccountController.java`; `bankaccount/dto/BankAccountRequest.java`, `BankAccountResponse.java`
- Create: `src/test/java/.../support/FinanceFixtures.java`
- Test: `bankaccount/controller/BankAccountControllerTest.java`

**Interfaces:**
- Consumes: `CurrentUser`, `AccountMemberRepository`, `PageResponse`, `FinanceValidation`.
- Produces:
  - `enum BankAccountType { CHECKING, SAVINGS, CASH, OTHER }`.
  - `record BankAccountRequest(@NotBlank @Size(max=60) String name, @NotNull BankAccountType type, @NotNull Long initialBalanceCents, UUID holderUserId)`.
  - `record BankAccountResponse(UUID id, String name, BankAccountType type, long initialBalanceCents, long balanceCents, UUID holderUserId, boolean archived, Instant createdAt, Instant updatedAt)`.
  - `BankAccountBalanceQuery.balances(UUID accountId, LocalDate asOf): Map<UUID, Long>` — um único `SELECT` agregado que aplica a fórmula do saldo a todas as contas da `Account` com `date <= asOf` (conta sem lançamentos → saldo inicial).
  - `BankAccountService.requireUsable(UUID accountId, UUID bankAccountId): BankAccount` (`404` se não existir na `Account`, `BANK_ACCOUNT_ARCHIVED` se arquivada) e `requireOwned(UUID accountId, UUID id): BankAccount` (`404` apenas) — usados pelas Tasks 4 e 6.
  - Endpoints: `GET /api/v1/bank-accounts?includeArchived=false` (ordem: não arquivadas primeiro, depois `name`), `GET /{id}`, `POST` → `201`, `PUT /{id}`, `POST /{id}/archive` → `200`, `POST /{id}/unarchive` → `200`, `DELETE /{id}` → `204` ou `409 BANK_ACCOUNT_IN_USE`.
  - `BankAccountDataProbe implements AccountDataProbe` (`true` se existir qualquer conta bancária).
  - `FinanceFixtures` (teste, `@TestComponent` importado por `@IntegrationTest`): esta task cria `bankAccount(TestSession, String name, long initialCents): UUID`; a Task 3 acrescenta `category(TestSession, String name, CategoryKind kind): UUID` e a Task 4, `transaction(TestSession, Map<String,Object> body): UUID`. Todos chamam a API real.

Regras: `holderUserId`, se informado, precisa ser membro atual da `Account` (senão `404`). `DELETE` tenta apagar e dar `flush`; a violação de FK (lançamento ou regra referenciando) vira `409 BANK_ACCOUNT_IN_USE`, sem checagem prévia, o que também cobre a corrida com um lançamento sendo criado.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void create_returns201WithBalanceEqualToInitial() { /* initialBalanceCents -5000 → balanceCents -5000 */ }
@Test void list_hidesArchivedUnlessRequested() {}
@Test void balance_appliesFormulaAndIgnoresFutureDates() {
    // inicial 10000; via SQL: INCOME 2000 hoje, EXPENSE 500 ontem, TRANSFER 1000 para outra conta, TRANSFER 300 recebida,
    // EXPENSE 9999 amanhã → balanceCents 10000 + 2000 − 500 − 1000 + 300 = 10800; a conta destino ganha +1000
}
@Test void holderMustBeMember() { /* holderUserId de outro usuário → 404 */ }
@Test void update_replacesAllFields() {}
@Test void archiveAndUnarchive() {}
@Test void delete_unused_returns204() {}
@Test void delete_referencedByTransaction_returns409InUse() { /* INSERT via SQL de um lançamento usando a conta */ }
@Test void otherAccountsBankAccount_returns404OnGetPutDeleteArchive() {}
@Test void validation_nameBlankOrTooLong_returns400() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest=BankAccountControllerTest` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): bank accounts with computed balances"
```

---

### Task 3: CRUD de categorias

**Files:**
- Create: `category/service/CategoryService.java`; `category/controller/CategoryController.java`; `category/dto/CategoryRequest.java`, `UpdateCategoryRequest.java`, `CategoryResponse.java`
- Modify: `category/repository/CategoryRepository.java`
- Test: `category/controller/CategoryControllerTest.java`

**Interfaces:**
- Consumes: `Category`/`CategoryKind`/`CategoryRepository` do plano 1; `CurrentUser`.
- Produces:
  - `record CategoryRequest(@NotBlank @Size(max=60) String name, @NotNull CategoryKind kind, @NotNull @Pattern(...) String color, @NotNull @Pattern(...) String icon)`; `record UpdateCategoryRequest(name, color, icon)` — o `kind` não muda depois de criado.
  - `record CategoryResponse(UUID id, String name, CategoryKind kind, String color, String icon, boolean systemDefault, boolean archived)`.
  - `CategoryService.requireUsable(UUID accountId, UUID categoryId, CategoryKind expectedKind): Category` (`404` se não for da `Account`, `CATEGORY_ARCHIVED` se arquivada, `CATEGORY_KIND_MISMATCH` se o `kind` diferir) e `requireOwned(UUID accountId, UUID id): Category` — usados pelas Tasks 4, 6 e 8.
  - Endpoints: `GET /api/v1/categories?kind=&includeArchived=false` (ordem `kind`, depois `name` case-insensitive), `GET /{id}`, `POST` → `201`, `PUT /{id}`, `POST /{id}/archive`, `POST /{id}/unarchive`, `DELETE /{id}` → `204` ou `409 CATEGORY_IN_USE`.

Regras: nome duplicado entre ativas do mesmo `kind`, ignorando maiúsculas, → `409 CATEGORY_NAME_TAKEN` (inclusive no `unarchive` e no `PUT`). Detecte pela violação de `uq_categories_active_name` (nome do constraint), não por consulta prévia. `DELETE` segue o mesmo padrão da Task 2 (violação de FK → `CATEGORY_IN_USE`); o orçamento da categoria cai junto pelo cascade.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void list_returnsSeededDefaultsForNewUser() { /* 13 categorias, 9 EXPENSE, 4 INCOME */ }
@Test void create_duplicateNameIgnoringCase_returns409() { /* "mercado" EXPENSE com "Mercado" padrão → CATEGORY_NAME_TAKEN */ }
@Test void create_sameNameOtherKind_isAllowed() {}
@Test void unarchive_whenNameTaken_returns409() {}
@Test void update_keepsKind() { /* PUT com nome/cor/ícone novos → kind original inalterado */ }
@Test void delete_unused_returns204_andReferenced_returns409() {}
@Test void invalidColorOrIcon_returns400WithFieldErrors() { /* errors[].field "color" */ }
@Test void otherAccountsCategory_returns404() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest=CategoryControllerTest` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): category CRUD with archive and case-insensitive names"
```

---

### Task 4: Lançamentos — criar, ler, editar, excluir

**Files:**
- Create: `transaction/model/Transaction.java`, `TransactionKind.java`; `transaction/repository/TransactionRepository.java`; `transaction/service/TransactionService.java`, `TransactionReferenceValidator.java`, `TransactionDataProbe.java`; `transaction/controller/TransactionController.java`; `transaction/dto/TransactionRequest.java`, `TransactionResponse.java`, `TransactionResponseAssembler.java`
- Test: `transaction/controller/TransactionCrudTest.java`

**Interfaces:**
- Consumes: `CategoryService.requireUsable/requireOwned`, `BankAccountService.requireUsable/requireOwned`, `FinanceValidation`.
- Produces:
  - `enum TransactionKind { INCOME, EXPENSE, TRANSFER }`.
  - `record TransactionRequest(@NotNull TransactionKind kind, @NotNull @Min(1) @Max(100_000_000_000L) Long amountCents, @NotNull LocalDate date, @Size(max=140) String description, UUID categoryId, UUID bankAccountId, UUID destinationBankAccountId)`.
  - `record TransactionResponse(UUID id, TransactionKind kind, long amountCents, LocalDate date, String description, CategoryRef category, BankAccountRef bankAccount, BankAccountRef destinationBankAccount, AuthorRef createdBy, UUID recurringRuleId, Instant createdAt, Instant updatedAt)`, com `record CategoryRef(UUID id, String name, String color, String icon, boolean archived)`, `record BankAccountRef(UUID id, String name, boolean archived)` e `record AuthorRef(UUID userId, String displayName)`. `createdBy` é `null` quando o autor foi excluído; a interface mostra "Ex-membro".
  - `TransactionReferenceValidator.validate(UUID accountId, TransactionKind kind, UUID categoryId, UUID bankAccountId, UUID destinationBankAccountId, ReferenceSnapshot current)`: aplica as regras de forma (`TRANSFER_INVALID` se faltar origem ou destino, se forem iguais ou se vier `categoryId`; `VALIDATION_FAILED` com `errors[].field` `categoryId` se faltar em `INCOME`/`EXPENSE`), as de posse (`404`) e as de arquivamento. `ReferenceSnapshot current` traz as referências que o lançamento já tem (vazio na criação); uma referência arquivada igual à atual é aceita. Usado também pela Task 6.
  - `TransactionResponseAssembler.toResponses(UUID accountId, List<Transaction>): List<TransactionResponse>`: carrega categorias, contas bancárias e autores em lote (uma consulta por tipo, nunca uma por lançamento). Usado pelas Tasks 5 e 9.
  - Endpoints: `POST /api/v1/transactions` → `201`, `GET /{id}`, `PUT /{id}`, `DELETE /{id}` → `204`.
  - `TransactionDataProbe implements AccountDataProbe`.

Regras: `created_by` é o `userId` do autor da criação e não muda na edição. A data precisa estar entre `MIN_DATE` e `MAX_DATE` (senão `VALIDATION_FAILED`, campo `date`). Ao editar um lançamento gerado por regra, ele mantém `recurring_rule_id`; se a nova data já tiver outra ocorrência da mesma regra (violação de `uq_transactions_rule_date`) → `409 RECURRING_DATE_TAKEN`.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void createExpense_returns201WithAuthorAndRefs() {}
@Test void createIncome_withExpenseCategory_returns400KindMismatch() {}
@Test void createIncomeWithoutCategory_returns400FieldCategoryId() {}
@Test void transfer_requiresDistinctOriginAndDestination() { /* sem destino, iguais, com categoryId → TRANSFER_INVALID */ }
@Test void create_withArchivedCategory_returns409() {}
@Test void update_keepingArchivedCategory_isAllowed_butSwitchingToArchived_isNot() {}
@Test void create_withOtherAccountsCategory_returns404() {}
@Test void create_withOtherAccountsBankAccount_returns404() {}
@Test void amountZeroOrNegative_returns400() {}
@Test void dateOutOfRange_returns400() { /* 1969-12-31 e 2101-01-01 */ }
@Test void partnerSeesAndEditsTransaction_createdByStaysOriginalAuthor() { /* TestUsers.couple */ }
@Test void deletedAuthor_showsNullCreatedBy() {}
@Test void otherAccountsTransaction_returns404() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest=TransactionCrudTest` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): income, expense and transfer transactions"
```

---

### Task 5: Listagem de lançamentos com filtros, busca e paginação

**Files:**
- Create: `transaction/repository/TransactionSpecifications.java`, `transaction/service/TransactionQueryService.java`, `transaction/dto/TransactionFilter.java`
- Modify: `transaction/repository/TransactionRepository.java` (`JpaSpecificationExecutor`), `transaction/controller/TransactionController.java`
- Test: `transaction/controller/TransactionListTest.java`

**Interfaces:**
- Consumes: `TransactionResponseAssembler`, `PageResponse`, `PageRequests`.
- Produces:
  - `record TransactionFilter(LocalDate from, LocalDate to, TransactionKind kind, UUID categoryId, UUID bankAccountId, UUID createdBy, String q)`.
  - `GET /api/v1/transactions?from&to&kind&categoryId&bankAccountId&createdBy&q&page&size` → `PageResponse<TransactionResponse>`, ordenado por `date DESC, createdAt DESC, id DESC`.
  - `TransactionQueryService.list(UUID accountId, TransactionFilter filter, int page, int size): PageResponse<TransactionResponse>`.

Regras: `from`/`to` são inclusivos; `from > to` → `VALIDATION_FAILED` (campo `from`). `bankAccountId` casa origem **ou** destino. `q` (trim, máx. 100 caracteres) faz `lower(description) LIKE lower(:q) ESCAPE '\'`, com `\`, `%` e `_` escapados antes de envolver em `%...%`. Um `categoryId` ou `bankAccountId` de outra `Account` responde `404 NOT_FOUND` (como qualquer referência a recurso de outra `Account`). Um `createdBy` de fora da `Account` não dá erro: o filtro sempre inclui `account_id = :accountId`, então a resposta vem vazia. A página usa o assembler em lote (no máximo 4 consultas além da contagem).

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void list_ordersByDateDescThenNewest() {}
@Test void list_filtersByDateRangeInclusive() {}
@Test void list_filtersByKindCategoryAndAuthor() {}
@Test void list_bankAccountMatchesOriginOrDestination() {}
@Test void list_qIsLiteralAndCaseInsensitive() { /* descrições "Pizza 50%", "pizza", "50 euros" → q="50%" só a 1ª; q="PIZZA" as 2 primeiras; q="_" nenhuma */ }
@Test void list_paginates() { /* 7 lançamentos, size=3 → totalElements 7, totalPages 3, page 2 tem 1 */ }
@Test void list_neverShowsOtherAccounts() { /* filtro com categoryId ou bankAccountId de outra Account → 404 NOT_FOUND; createdBy de fora → content vazio */ }
@Test void list_fromAfterTo_returns400() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest=TransactionListTest` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): transaction listing with filters, literal search and paging"
```

---

### Task 6: Regras recorrentes — calendário, CRUD e geração imediata

**Files:**
- Create: `recurring/model/RecurringRule.java`, `Frequency.java`; `recurring/repository/RecurringRuleRepository.java`; `recurring/service/RecurrenceSchedule.java`, `RecurringRuleService.java`, `RecurringGenerator.java`, `RecurringRuleDataProbe.java`; `recurring/controller/RecurringRuleController.java`; `recurring/dto/CreateRecurringRuleRequest.java`, `UpdateRecurringRuleRequest.java`, `RecurringRuleResponse.java`
- Modify: `bankaccount/service/BankAccountService.java`, `category/service/CategoryService.java` (bloqueio de arquivamento)
- Test: `recurring/service/RecurrenceScheduleTest.java` (unitário), `recurring/controller/RecurringRuleControllerTest.java`

**Interfaces:**
- Consumes: `TransactionReferenceValidator.validate`, `TransactionKind`.
- Produces:
  - `enum Frequency { WEEKLY, MONTHLY, YEARLY }`.
  - `RecurrenceSchedule` (classe sem estado, métodos estáticos):
    - `occurrence(LocalDate start, Frequency f, int k): LocalDate` = `start.plusWeeks/plusMonths/plusYears(k)`.
    - `firstOnOrAfter(LocalDate start, Frequency f, LocalDate date): LocalDate` = a menor ocorrência `>= date` (devolve `start` se `date <= start`).
    - `between(LocalDate start, Frequency f, LocalDate endDate /*nullable*/, LocalDate from, LocalDate to): List<LocalDate>`: ocorrências em `[from, to]` que não passam de `endDate`.
  - `record CreateRecurringRuleRequest(@NotNull TransactionKind kind, amountCents (como na Task 4), @Size(max=140) String description, UUID categoryId, UUID bankAccountId, UUID destinationBankAccountId, @NotNull Frequency frequency, @NotNull LocalDate startDate, LocalDate endDate)`.
  - `record UpdateRecurringRuleRequest(amountCents, description, categoryId, bankAccountId, destinationBankAccountId, LocalDate endDate)` — `kind`, `frequency` e `startDate` não mudam; para isso, cria-se outra regra.
  - `record RecurringRuleResponse(UUID id, TransactionKind kind, long amountCents, String description, CategoryRef category, BankAccountRef bankAccount, BankAccountRef destinationBankAccount, Frequency frequency, LocalDate startDate, LocalDate endDate, LocalDate nextRunDate, boolean active, boolean finished, AuthorRef createdBy)`, em que `finished = endDate != null && nextRunDate.isAfter(endDate)`. Reaproveita os `*Ref` da Task 4.
  - `RecurringGenerator.generateDue(UUID ruleId, LocalDate today): int`: na transação do chamador, trava a regra (`PESSIMISTIC_WRITE`) e, se ela estiver ativa, insere todas as ocorrências em `[nextRunDate, min(today, endDate)]` com `INSERT ... ON CONFLICT (recurring_rule_id, date) WHERE recurring_rule_id IS NOT NULL DO NOTHING` (SQL nativo). Depois grava `nextRunDate = firstOnOrAfter(start, f, today + 1)` e devolve quantas linhas foram inseridas. Os lançamentos gerados copiam `kind`, valor, descrição e referências da regra, com `created_by = rule.created_by`.
  - Endpoints: `GET /api/v1/recurring-rules` (ordem `active DESC, nextRunDate ASC`), `GET /{id}`, `POST` → `201`, `PUT /{id}`, `POST /{id}/activate`, `POST /{id}/deactivate`, `DELETE /{id}` → `204`.
  - `RecurringRuleDataProbe implements AccountDataProbe`.
  - `CategoryService.archive` e `BankAccountService.archive` passam a responder `409 IN_USE_BY_ACTIVE_RULE` se uma regra ativa e não terminada referenciar o recurso.

Regras:
- **Criação:** valida as referências com `ReferenceSnapshot` vazio; `endDate < startDate` → `VALIDATION_FAILED` (campo `endDate`); `nextRunDate = startDate`. Na mesma transação, chama `generateDue(rule, today)`, que gera de imediato as ocorrências com data `<= hoje`.
- **Edição:** afeta só as ocorrências futuras; os lançamentos já gerados não mudam.
- **Ativar:** `nextRunDate = firstOnOrAfter(start, f, today)`, sem recuperar ocorrências perdidas, e em seguida `generateDue`.
- **Desativar:** marca `active = false`.
- **Excluir:** os lançamentos gerados ficam, com `recurring_rule_id = NULL` (FK).

- [ ] **Step 1: Escrever os testes que falham**

```java
// RecurrenceScheduleTest
@Test void monthlyOn31st_clampsAndRecovers() { /* start 2027-01-31: k=1 → 2027-02-28, k=2 → 2027-03-31; start 2028-01-31 k=1 → 2028-02-29 */ }
@Test void yearlyOnLeapDay_clamps() { /* 2028-02-29: k=1 → 2029-02-28, k=4 → 2032-02-29 */ }
@Test void weekly() {}
@Test void firstOnOrAfter_returnsStartWhenDateBeforeStart() {}
@Test void between_respectsEndDate() {}
// RecurringRuleControllerTest (clock em 2027-03-15)
@Test void create_withPastStart_generatesAllDueOccurrencesImmediately() { /* MONTHLY start 2027-01-31 → lançamentos 01-31, 02-28; nextRunDate 2027-03-31 */ }
@Test void create_withFutureStart_generatesNothing() {}
@Test void update_affectsOnlyFutureOccurrences() { /* valor novo; lançamentos existentes mantêm o antigo */ }
@Test void deactivateThenActivate_skipsMissedOccurrences() { /* desativa, avança 2 meses, ativa → só a de hoje em diante */ }
@Test void delete_keepsGeneratedTransactionsDetached() {}
@Test void endDateBeforeStart_returns400() {}
@Test void archiveCategoryUsedByActiveRule_returns409() {}
@Test void transferRule_validatesLikeTransactions() {}
@Test void otherAccountsRule_returns404() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest='RecurrenceScheduleTest,RecurringRuleControllerTest'` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): recurring rules with month-end-safe schedule and immediate generation"
```

---

### Task 7: Job diário de recorrência

**Files:**
- Create: `recurring/service/RecurringGenerationJob.java`, `recurring/service/SchedulingConfig.java`
- Modify: `recurring/repository/RecurringRuleRepository.java`, `application.yml`, `src/test/resources/application-test.yml`
- Test: `recurring/service/RecurringGenerationJobTest.java`

**Interfaces:**
- Consumes: `RecurringGenerator.generateDue`, `EntitlementService.hasAccess`.
- Produces:
  - `SchedulingConfig`: `@EnableScheduling` condicionado a `app.recurring.scheduler-enabled` (padrão `true`; `false` em `application-test.yml`).
  - `RecurringGenerationJob.run(): JobResult` com `@Scheduled(cron = "0 15 0 * * *", zone = "UTC")`; `record JobResult(int rules, int generated, int skippedNoAccess, int failed)`.
  - `RecurringRuleRepository.findDueRuleIds(LocalDate today): List<UUID>` (`active` e `next_run_date <= today`).

Regras: cada regra roda na sua própria transação (`TransactionTemplate`), de modo que uma falha é registrada em `ERROR` com o `ruleId` e não interrompe as demais. Uma `Account` sem acesso (`EntitlementService.hasAccess == false`) é pulada sem avançar `nextRunDate`. Os testes chamam `run()` diretamente.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void job_generatesDueOccurrencesAcrossAccounts() { /* 2 Accounts com regras; clock avança 1 mês → run() → cada uma ganha 1 lançamento */ }
@Test void job_runTwice_isIdempotent() { /* run(); run() → generated total igual ao do primeiro run */ }
@Test void job_skipsAccountsWithoutAccess() { /* subscription EXPIRED → skippedNoAccess 1; nenhum lançamento; nextRunDate inalterado */ }
@Test void job_catchesUpMissedDays() { /* WEEKLY; clock +3 semanas → 3 lançamentos */ }
@Test void job_oneFailingRuleDoesNotStopOthers() { /* @MockitoSpyBean RecurringGenerator lança para um ruleId → failed 1; a outra regra gera normalmente */ }
@Test void job_respectsEndDate() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest=RecurringGenerationJobTest` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): daily recurring generation job with entitlement skip"
```

---

### Task 8: Orçamentos por categoria

**Files:**
- Create: `budget/model/Budget.java`; `budget/repository/BudgetRepository.java`, `BudgetProgressQuery.java`; `budget/service/BudgetService.java`, `BudgetDataProbe.java`; `budget/controller/BudgetController.java`; `budget/dto/CreateBudgetRequest.java`, `UpdateBudgetRequest.java`, `BudgetResponse.java`
- Test: `budget/controller/BudgetControllerTest.java`

**Interfaces:**
- Consumes: `CategoryService.requireUsable(accountId, categoryId, EXPENSE)`.
- Produces:
  - `record CreateBudgetRequest(@NotNull UUID categoryId, @NotNull @Min(1) @Max(100_000_000_000L) Long monthlyLimitCents)`; `record UpdateBudgetRequest(@NotNull ... Long monthlyLimitCents)`.
  - `enum BudgetStatus { NORMAL, WARNING, EXCEEDED }` com `static BudgetStatus of(int percent)`.
  - `record BudgetResponse(UUID id, CategoryRef category, long monthlyLimitCents, YearMonth month, long spentCents, int percent, BudgetStatus status)`.
  - `BudgetProgressQuery.spentByCategory(UUID accountId, YearMonth month): Map<UUID, Long>` — `Σ amount_cents` de `EXPENSE` com `date` dentro do mês, agrupado por categoria, numa única consulta. Usado pela Task 9.
  - Endpoints: `GET /api/v1/budgets?month=YYYY-MM` (padrão: mês de hoje), `GET /{id}?month=`, `POST` → `201`, `PUT /{id}`, `DELETE /{id}` → `204`.
  - `BudgetDataProbe implements AccountDataProbe`.

Regras: categoria `INCOME` → `400 BUDGET_CATEGORY_INVALID` (traduza o `CATEGORY_KIND_MISMATCH` de `requireUsable` para esse código); categoria arquivada → `CATEGORY_ARCHIVED`; segundo orçamento para a mesma categoria → `409 BUDGET_EXISTS`, detectado pelo constraint único. O progresso considera todo o mês, incluindo datas futuras dentro dele.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void create_andListWithProgress() { /* limite 10000; despesas 7900 no mês → percent 79, NORMAL */ }
@Test void status_warningAt80_exceededAt100() { /* 8000 → WARNING; 10000 → EXCEEDED; 15000 → percent 150 */ }
@Test void progress_ignoresOtherMonthsIncomeAndTransfers() {}
@Test void incomeCategory_returns400BudgetCategoryInvalid() {}
@Test void duplicateCategory_returns409BudgetExists() {}
@Test void deletingCategory_cascadesBudget() { /* categoria sem lançamentos com orçamento → DELETE categoria 204 → orçamento some */ }
@Test void otherAccountsBudget_returns404() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest=BudgetControllerTest` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): monthly category budgets with progress"
```

---

### Task 9: Dashboard mensal

**Files:**
- Create: `dashboard/service/DashboardService.java`; `dashboard/controller/DashboardController.java`; `dashboard/dto/DashboardResponse.java`
- Test: `dashboard/controller/DashboardControllerTest.java`

**Interfaces:**
- Consumes: `BankAccountBalanceQuery.balances`, `BudgetProgressQuery.spentByCategory`, `BudgetRepository`, `RecurringRuleRepository`, `RecurrenceSchedule.between`.
- Produces:
  - `GET /api/v1/dashboard?month=YYYY-MM` (padrão: mês de hoje; formato inválido → `400 VALIDATION_FAILED`) → `record DashboardResponse(YearMonth month, String currency, LocalDate balancesAsOf, long totalBalanceCents, List<AccountBalance> accounts, long incomeCents, long expenseCents, List<CategorySpend> spendingByCategory, List<BudgetResponse> budgets, List<UpcomingOccurrence> upcoming)`, com:
    - `record AccountBalance(UUID id, String name, BankAccountType type, long balanceCents)`;
    - `record CategorySpend(UUID categoryId, String name, String color, String icon, long totalCents)`;
    - `record UpcomingOccurrence(UUID ruleId, LocalDate date, TransactionKind kind, long amountCents, String description, UUID categoryId)`.

Regras:
- **Saldos:** `balancesAsOf = min(hoje, último dia do mês)`. Saldos e total cobrem apenas as contas não arquivadas.
- **Totais do mês:** `incomeCents`, `expenseCents` e `spendingByCategory` somam todos os lançamentos com `date` dentro do mês, inclusive datas futuras desse mês, e excluem transferências.
- **Gastos por categoria:** ordenados por `totalCents DESC`.
- **Orçamentos:** `budgets` reusa `BudgetResponse` do mês.
- **Próximas recorrências:** `upcoming` independe de `month`. São as ocorrências das regras ativas em `[nextRunDate, hoje + 30]`, nunca antes de hoje, ordenadas por data e limitadas a 20 itens.
- **Desempenho:** o endpoint faz no máximo 8 consultas, independentemente do volume de dados.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test void dashboard_currentMonth_aggregatesEverything() { /* contas, receitas, despesas, transferência; confere cada campo */ }
@Test void dashboard_pastMonth_balancesAsOfMonthEnd() { /* lançamento depois do fim do mês não entra no saldo daquele mês */ }
@Test void dashboard_excludesTransfersFromTotalsAndArchivedAccountsFromBalances() {}
@Test void dashboard_upcomingNext30Days() { /* WEEKLY ativa → 4 ou 5 ocorrências; regra inativa não aparece */ }
@Test void dashboard_invalidMonth_returns400() { /* month=2026-13 */ }
@Test void dashboard_emptyAccount_returnsZeros() {}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw test -Dtest=DashboardControllerTest` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): monthly dashboard aggregates"
```

---

### Task 10: Isolamento entre `Account`s, gate de assinatura e ciclo de vida com dados

**Files:**
- Test: `isolation/AccountIsolationTest.java`, `subscription/FinanceEntitlementTest.java`, `account/controller/FinanceLifecycleTest.java`
- Modify: apenas o que os testes revelarem.

**Interfaces:**
- Consumes: todos os endpoints das Tasks 2–9, `TestUsers.couple`, `FinanceFixtures`.

Regras:
- **`AccountIsolationTest`:** é a suíte dedicada da spec (seção 6). Um teste parametrizado (`@ParameterizedTest` + `@MethodSource`) percorre todas as rotas com `{id}` das Tasks 2, 3, 4, 6 e 8 — `GET`, `PUT`, `DELETE` e as ações `archive`, `unarchive`, `activate` e `deactivate` — usando o ID de um recurso de outra `Account`, e espera sempre `404` com `code NOT_FOUND`.
- **`FinanceEntitlementTest`:** com `subscription.status = EXPIRED`, cada um dos seis prefixos financeiros responde `402 SUBSCRIPTION_INACTIVE`. Com `TRIALING`, há acesso.
- **`FinanceLifecycleTest`:** cobre o ciclo de vida da `Account` com dados financeiros:
  - aceitar convite com dados financeiros na `Account` solo exige `confirmDiscard`;
  - quem sai do casal fica sem os dados, que permanecem com o parceiro;
  - o único membro com dados se exclui → `204`, e todas as tabelas financeiras ficam vazias para aquela `Account`;
  - excluir o parceiro mantém os lançamentos dele com `createdBy = null`.

- [ ] **Step 1: Escrever os testes**

```java
@ParameterizedTest @MethodSource("idRoutes") void foreignId_returns404(String method, String pathTemplate) {}
@ParameterizedTest @ValueSource(strings = {"/api/v1/bank-accounts", "/api/v1/categories", "/api/v1/transactions", "/api/v1/recurring-rules", "/api/v1/budgets", "/api/v1/dashboard"})
void inactiveSubscription_returns402(String path) {}
@Test void trialing_hasAccess() {}
@Test void accept_withFinanceDataInSoloAccount_requiresConfirmDiscard() {}
@Test void leave_leaverLosesData_partnerKeepsIt() {}
@Test void deleteMe_soleMemberWithFinanceData_returns204() {}
@Test void deletePartner_keepsTransactionsWithNullAuthor() {}
```

- [ ] **Step 2: Rodar** — `./mvnw test -Dtest='AccountIsolationTest,FinanceEntitlementTest,FinanceLifecycleTest'`. Qualquer falha é bug das tasks anteriores: corrija na causa e acrescente o teste unitário que faltava.
- [ ] **Step 3: Rodar a suíte completa e o Spotless**

Run: `./mvnw spotless:apply && ./mvnw verify`
Expected: `BUILD SUCCESS`, todos os testes verdes.

- [ ] **Step 4: Commit**

```bash
git add backend/src
git commit -m "test(backend): cross-account isolation, entitlement and lifecycle with finance data"
```
