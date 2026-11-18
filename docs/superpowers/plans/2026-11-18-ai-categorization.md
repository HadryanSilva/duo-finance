# Categorização por IA no import — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** no preview do import da Revolut, compras com cartão de comerciantes novos chegam à revisão
com categoria sugerida. A sugestão vem primeiro de uma memória parecida e, se não houver, da IA
(Claude Haiku). A linha sugerida pela IA aparece com selo, e o casal pode desligar a IA nas
Configurações.

**Architecture:** o `ImportPlanner`, que não acessa o banco, passa a resolver `MEMORY`, `SIMILAR`,
`DEFAULT` e `NONE` e a marcar as linhas elegíveis para a IA. Depois do preview transacional, um
`AiSuggestionService`, fora da transação, junta as candidatas, chama um `MerchantCategorizer` e
aplica o resultado com `ImportPlanner.applyAiSuggestions`. O `MerchantCategorizer` tem duas
implementações: Claude, com cache Caffeine, e no-op, usada quando não há chave de API. O commit
replaneja sem IA e só grava "Outros" na memória quando o casal o escolheu ativamente.

**Tech Stack:** Java 25, Spring Boot, JdbcTemplate/JPA, Flyway, `com.anthropic:anthropic-java`,
Caffeine, Vue 3 + vue-i18n + TanStack Query, Vitest, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-18-ai-categorization-design.md`

## Global Constraints

- Branch `feature/auto-categorization`, criada a partir de `origin/develop`. O PR vai para a **`develop`**.
- Modelo `claude-haiku-5-5`, esforço `low`, timeout total de 6 s, `maxRetries = 0`, no máximo
  150 descrições distintas por preview e descrição truncada em 100 caracteres.
- Para a IA vão só índices, descrições e nomes de categorias. Nunca UUID, valor, data ou usuário.
- Elegível para a IA: `kind = EXPENSE`, `Type` canônico `CARD_PAYMENT`, linha que não é tarifa,
  status `NEW` ou `POSSIBLE_DUPLICATE`, sem sugestão de memória exata nem parecida, e conta com
  `ai_suggestions_enabled = true`.
- Memória que aponta para a categoria padrão do tipo ("Outros"/"Outras receitas") é ignorada nas
  etapas de memória exata e parecida. Resposta da IA igual à padrão conta como `null`.
- `SIMILAR`: só para linhas elegíveis; maior prefixo comum de palavras inteiras, com **2 ou mais**
  palavras; desempate pelo `updated_at` mais recente; o prefixo comum não pode ser exatamente um de
  `GENERIC_PREFIXES = ["transfer to","transfer from","payment from","cash withdrawal","to pocket","transferencia para","transferencia de","pagamento de","levantamento de"]`.
- Propriedades no prefixo existente `app` (`AppProperties`): `app.ai.anthropic-api-key`
  (`${ANTHROPIC_API_KEY:}`), `app.ai.model`, `app.ai.timeout` (6s), `app.ai.max-items` (150). A IA
  fica ativa só com a chave não vazia.
- Valores de `suggestionSource`: `MEMORY`, `SIMILAR`, `AI`, `DEFAULT`, `NONE`.
- Commits terminam com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Use `rtk` nos
  comandos.

## Review Focus

- **Extrato em português:** o `Type` traduzido ("Pagamento com cartão") precisa virar `CARD_PAYMENT`.
  Sem isso, a feature não faz nada para quem exporta em pt. A Task 1 testa isso no parser.
- **Tarifa Revolut** de uma compra com cartão (linha gerada com `feeDescription`): não pode ir para a
  IA. A Task 1 testa isso.
- **IA lenta ou fora do ar:** o preview precisa responder em até cerca de 6 s, com as linhas em
  `DEFAULT`. A Task 4 testa timeout, erro HTTP e JSON inválido.
- **Categoria arquivada ou de receita na resposta da IA,** ou índice fora da lista: ignorada. Testado
  na Task 4 (índices) e na Task 5 (só categorias de despesa ativas, sem a padrão, são enviadas).
- **Casal desliga a opção com um preview já em cache:** nada é enviado e nenhuma linha sai `AI`.
  Testado na Task 5.

---

### Task 1: planner — elegibilidade, memória "não sei", SIMILAR e origem da sugestão

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/imports/service/RevolutCsvParser.java`
  (`TYPE_ALIASES`)
- Modify: `.../imports/service/PlannedRow.java`, `PlanContext.java`, `ImportPlanner.java`
- Create: `.../imports/service/SuggestionSource.java`, `.../imports/service/SimilarMemory.java`
- Modify: `.../imports/repository/CategoryMemoryRepository.java` (`findActive` traz `updated_at`)
- Modify: `.../imports/service/ImportContextLoader.java`, `.../imports/dto/PreviewResponse.java`
- Test: `backend/src/test/java/br/dev/duofinance/imports/service/ImportPlannerTest.java`,
  `RevolutCsvParserTest.java`, new `SimilarMemoryTest.java`

**Interfaces:**
- Produces:
  - `enum SuggestionSource { MEMORY, SIMILAR, AI, DEFAULT, NONE }`
  - `PlanContext.RememberedCategory(UUID categoryId, Instant updatedAt)`; `PlanContext.memory`
    passa a ser `Map<MemoryKey, RememberedCategory>`
  - `PlannedRow` ganha os campos `SuggestionSource suggestionSource` e `boolean aiEligible`
    (os dois no final do record) e o método `boolean needsAi()` =
    `aiEligible && (suggestionSource == DEFAULT || suggestionSource == NONE)`
  - `static ImportPlan ImportPlanner.applyAiSuggestions(ImportPlan plan, Map<String, UUID> byDescriptionKey)`:
    nas linhas com `needsAi()` cuja chave está no mapa, troca a categoria e marca origem `AI`;
    as demais ficam iguais
  - `static Optional<UUID> SimilarMemory.find(String descriptionKey, TransactionKind kind, Map<MemoryKey, RememberedCategory> memory, UUID defaultCategory)`
  - `PreviewResponse.PreviewRow` ganha `SuggestionSource suggestionSource`

- [ ] **Step 1: Escrever os testes que falham**
  - `RevolutCsvParserTest.localizedCardPayment_isCanonical`: uma linha `Pagamento com cartão` sai
    com `type() == "CARD_PAYMENT"`.
  - `SimilarMemoryTest`:
    - `twoWordPrefix_matches`: "pingo doce lisboa" na memória → "pingo doce porto" encontra.
    - `oneWordPrefix_doesNotMatch`: "uber" → "uber eats lisboa" vazio.
    - `genericPrefixOnly_doesNotMatch`: "transfer to joao" → "transfer to maria" vazio.
    - `longestPrefixWins`: com "pingo doce" e "pingo doce porto centro", a chave "pingo doce porto
      norte" casa com a segunda.
    - `tieBreak_mostRecent`: duas entradas com o mesmo tamanho de prefixo → a de `updatedAt` maior.
    - `defaultCategoryEntries_ignored` e `otherKind_ignored`.
  - `ImportPlannerTest`:
    - `memoryExact_sourceMemory`.
    - `memoryPointingToDefault_isIgnored_fallsToDefault`: a linha sai `DEFAULT`, `aiEligible = true`.
    - `similar_onlyForCardPayment`: um `TRANSFER`/`TOPUP` com prefixo parecido sai `DEFAULT`.
    - `cardPaymentExpense_isAiEligible`.
    - `income_notEligible`.
    - `fee_notEligible`.
    - `alreadyImported_notEligible`.
    - `noDefaultCategory_sourceNone`.
    - `applyAiSuggestions_onlyTouchesRowsThatNeedAi`: uma linha `MEMORY` com a chave no mapa fica
      intacta.
    - `applyAiSuggestions_marksAi`.

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && ./mvnw -B -q test -Dtest='ImportPlannerTest,SimilarMemoryTest,RevolutCsvParserTest'`
Expected: falha de compilação (`SuggestionSource`/`SimilarMemory` não existem).

- [ ] **Step 3: Implementar**
  - `TYPE_ALIASES` ganha `"Pagamento com cartão" → "CARD_PAYMENT"`.
  - O `build` do planner recebe um flag `boolean fee`. Elegível =
    `!fee && kind == EXPENSE && "CARD_PAYMENT".equals(row.type()) && status != ALREADY_IMPORTED`.
  - Ordem da sugestão: memória exata (exceto a padrão) → `SimilarMemory`, só se elegível → padrão →
    nenhuma.
  - Em `CategoryMemoryRepository.findActive`, o `SELECT` passa a incluir `m.updated_at`.
  - Ajustar os construtores de `PlanContext` nos testes existentes.

- [ ] **Step 4: Rodar e ver passar** (o mesmo comando, mais `ImportPreviewTest` e `ImportCommitTest`
  para checar regressão)

Run: `cd backend && ./mvnw -B -q test -Dtest='Import*Test,SimilarMemoryTest,RevolutCsvParserTest'`
Expected: tudo verde.

- [ ] **Step 5: Commit** — `feat(imports): suggestion source, similar memory and AI eligibility in the planner`

### Task 2: commit — "Outros" só vai para a memória quando escolhido ativamente

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/imports/service/ImportCommitService.java`
  (o laço que monta `remembered`)
- Test: `backend/src/test/java/br/dev/duofinance/imports/controller/ImportCommitTest.java`

**Interfaces:**
- Consumes: `PlannedRow.suggestionSource()` e `PlanContext.defaultCategories()` (Task 1).

- [ ] **Step 1: Testes que falham**
  - `commit_defaultCategoryOnUnknownMerchant_doesNotWriteMemory`.
  - `commit_defaultChosenOverMemorySuggestion_writesMemory`: a memória tem "Mercado" e a decisão é
    "Outros" → a memória passa a ser "Outros".
  - `commit_nonDefaultCategory_writesMemory` (regressão).
- [ ] **Step 2: Rodar e ver falhar**
  Run: `cd backend && ./mvnw -B -q test -Dtest=ImportCommitTest`
  Expected: o primeiro teste falha, porque a memória é gravada.
- [ ] **Step 3: Implementar** — pular o `remembered.put` quando `d.categoryId()` é igual a
  `ctx.defaultCategories().get(row.kind())` e `row.suggestionSource()` está em `{DEFAULT, NONE}`.
  Para ter acesso ao `ctx` ali, guardar o contexto numa variável local antes de planejar.
- [ ] **Step 4: Rodar e ver passar** (o mesmo comando, verde)
- [ ] **Step 5: Commit** — `feat(imports): remember the default category only when actively chosen`

### Task 3: opção "Sugestões por IA" na conta (backend)

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__account_ai_suggestions.sql`
- Modify: `.../account/model/Account.java`, `.../account/dto/AccountResponse.java`,
  `.../account/dto/UpdateAccountRequest.java`, `.../account/service/AccountService.java`,
  `.../account/controller/AccountController.java`
- Test: `backend/src/test/java/br/dev/duofinance/account/controller/AccountControllerTest.java`

**Interfaces:**
- Produces:
  - coluna `accounts.ai_suggestions_enabled boolean NOT NULL DEFAULT true`;
  - `AccountResponse.aiSuggestionsEnabled` (último campo);
  - `UpdateAccountRequest(@Nullable @Size(max = 60) String name, @Nullable Boolean aiSuggestionsEnabled)`,
    com `name` não-branco quando presente e pelo menos um dos dois presente;
  - `AccountService.update(CurrentUser, UpdateAccountRequest) -> AccountResponse`, que substitui
    `rename`.

- [ ] **Step 1: Testes que falham**
  - `get_aiSuggestionsEnabledDefaultsTrue`.
  - `patch_onlyAiFlag_updatesAndKeepsName`.
  - `patch_onlyName_keepsAiFlag`.
  - `patch_empty_400` (`VALIDATION_FAILED`).
  - `patch_blankName_400`.
- [ ] **Step 2: Rodar e ver falhar**
  Run: `cd backend && ./mvnw -B -q test -Dtest=AccountControllerTest`
  Expected: falha de compilação ou asserção em `aiSuggestionsEnabled`.
- [ ] **Step 3: Implementar.** Para "pelo menos um", usar uma validação de classe no record (por
  exemplo `@AssertTrue boolean isAnyField()`) no padrão das outras DTOs. Manter `rename` só se
  houver outro chamador; se não houver, remover.
- [ ] **Step 4: Rodar e ver passar** (o mesmo comando, mais `FinanceLifecycleTest`)
- [ ] **Step 5: Commit** — `feat(account): ai suggestions setting`

### Task 4: `MerchantCategorizer` com Claude, no-op e cache

**Files:**
- Modify: `backend/pom.xml`. Adicionar `com.anthropic:anthropic-java` na versão mais recente da
  linha 3.x (conferir no Maven Central; 3.2.0 era a corrente na escrita) e
  `com.github.ben-manes.caffeine:caffeine` (versão gerenciada pelo Boot).
- Modify: `.../shared/config/AppProperties.java` (registro `Ai`),
  `backend/src/main/resources/application.yml`
- Create em `backend/src/main/java/br/dev/duofinance/imports/ai/`:
  - `MerchantCategorizer.java`
  - `NoOpMerchantCategorizer.java`
  - `ClaudeMerchantCategorizer.java`
  - `MerchantCategorizerConfiguration.java`
- Test: `backend/src/test/java/br/dev/duofinance/imports/ai/ClaudeMerchantCategorizerTest.java`

**Interfaces:**
- Produces:

```java
public interface MerchantCategorizer {
    /** descriptionKey -> chosen category id; keys without a confident answer are absent. Never throws. */
    Map<String, UUID> categorize(UUID accountId, List<Merchant> merchants, List<CategoryOption> categories);

    record Merchant(String descriptionKey, String description) {}
    record CategoryOption(UUID id, String name) {}
}
```

- `AppProperties.Ai(String anthropicApiKey, @DefaultValue("claude-haiku-5-5") String model, @DefaultValue("6s") Duration timeout, @DefaultValue("150") int maxItems, @DefaultValue("") String baseUrl)`.
  O `baseUrl` só é usado nos testes, quando não está vazio.
- `MerchantCategorizerConfiguration` cria `ClaudeMerchantCategorizer` quando a chave não está em
  branco e `NoOpMerchantCategorizer` caso contrário. O bean `Clock` já existente é injetado no
  Caffeine (via `Ticker`) para tornar o TTL testável.

- [ ] **Step 1: Testes que falham** (servidor HTTP local com `com.sun.net.httpserver.HttpServer`,
  que responde JSON fixo no formato da Messages API; o cliente aponta para ele via `baseUrl`):
  - `sendsOnlyIndexesDescriptionsAndCategoryNames`: o corpo recebido contém os nomes e as
    descrições, não contém nenhum UUID (regex de UUID) e usa `"model":"claude-haiku-5-5"`.
  - `truncatesDescriptionTo100Chars`.
  - `mapsIndexesBackToUuids`.
  - `ignoresOutOfRangeAndNullIndexes`.
  - `invalidJson_returnsEmpty`.
  - `http500_returnsEmpty_noRetry`: o servidor conta 1 requisição.
  - `slowServer_returnsEmptyWithinTimeout`: o servidor dorme 3 s com o timeout de teste em 1 s; o
    retorno vem em menos de 2 s.
  - `refusalOrMaxTokens_returnsEmpty`.
  - `secondCallSameKeys_servedFromCache`: 1 requisição.
  - `cacheKeyIncludesCategoryList`: mudar as categorias faz nova requisição.
  - `cacheStoresNoAnswerToo`: uma chave sem resposta não é pedida de novo.
  - `noOp_returnsEmpty`.
- [ ] **Step 2: Rodar e ver falhar**
  Run: `cd backend && ./mvnw -B -q test -Dtest=ClaudeMerchantCategorizerTest`
  Expected: falha de compilação.
- [ ] **Step 3: Implementar `ClaudeMerchantCategorizer`**
  - Ler antes a skill `claude-api`: `java/claude-api/README.md`, seções de cliente, timeout e
    `stop_reason`, e `tool-use.md`, seção Structured Outputs. Não adivinhar nomes de classes do SDK;
    compilar e corrigir.
  - Cliente: `AnthropicOkHttpClient.builder().apiKey(...).timeout(timeout).maxRetries(0)`, mais
    `baseUrl` quando configurado.
  - Requisição: `model`, `max_tokens` de 4000, `output_config` com `effort: low` e `format` com JSON
    schema `{"assignments":[{"item":int,"category":int|null}]}`.
  - System prompt em inglês: "You categorize card-payment merchants for a couple's budgeting app.
    For each item choose the most likely category index from the list, or null if no category is a
    reasonable fit. Never invent categories."
  - Mensagem do usuário: JSON com `categories:[{index,name}]` e `items:[{index,description}]`.
  - Considerar só `stop_reason == end_turn`. Capturar todas as exceções do SDK e registrar um warning
    com a classe e o status, sem o conteúdo.
  - Cache Caffeine: chave `(accountId, descriptionKey, hash da lista ordenada de nomes+ids)`, valor
    `Optional<UUID>`, TTL de 1 h, máximo de 10.000 entradas. Só as chaves que faltam no cache vão
    para a API.
- [ ] **Step 4: Rodar e ver passar** (o mesmo comando, verde)
- [ ] **Step 5: Commit** — `feat(imports): Claude merchant categorizer with cache and no-op fallback`

### Task 5: preview usa a IA fora da transação

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/imports/service/AiSuggestionService.java`
- Modify: `.../imports/controller/ImportController.java` (só o `preview`)
- Create: `backend/src/test/java/br/dev/duofinance/support/TestMerchantCategorizerConfiguration.java`
  (`@Primary RecordingMerchantCategorizer`, no padrão do `TestEmailConfiguration`; registrar onde o
  `TestEmailConfiguration` é registrado)
- Test: `backend/src/test/java/br/dev/duofinance/imports/controller/ImportPreviewTest.java`,
  `ImportCommitTest.java`
- Regenerate: `frontend/src/shared/api/schema.ts`

**Interfaces:**
- Consumes: `PlannedRow.needsAi()` e `ImportPlanner.applyAiSuggestions` (Task 1),
  `accounts.ai_suggestions_enabled` (Task 3), `MerchantCategorizer` e `AppProperties.Ai.maxItems`
  (Task 4).
- Produces: `ImportPlan AiSuggestionService.apply(UUID accountId, ImportPlan plan)`.
  - Sem `@Transactional`.
  - Lê o flag e as categorias de despesa ativas da conta, sem a padrão.
  - Junta as linhas `needsAi()` por `descriptionKey`, em ordem de aparição, até `maxItems`.
  - Se o flag estiver desligado, não houver candidatas ou não houver categorias, devolve o plano
    sem chamar o categorizador.
  - Descarta respostas que apontem para categorias fora da lista enviada.

- [ ] **Step 1: Testes que falham**
  - `ImportPreviewTest.preview_unknownCardMerchant_getsAiSuggestion`: o fake devolve "Mercado" para
    uma chave → a linha sai `suggestionSource: "AI"` com o id do Mercado.
  - `preview_sendsOnlyEligibleMerchants`: o fake registra a chamada; não aparecem
    `TRANSFER`/`TOPUP`/receita/tarifa, e as categorias enviadas são só de despesa, ativas e sem
    "Outros".
  - `preview_aiDisabled_neverCallsCategorizer` (flag desligado via `PATCH`).
  - `preview_respectsMaxItems` (propriedade de teste baixa, por exemplo 2).
  - `preview_categorizerAnswerOutsideList_ignored`.
  - `preview_noOp_sameAsBefore`: os testes existentes continuam verdes.
  - `ImportCommitTest.commit_neverCallsCategorizer`.
- [ ] **Step 2: Rodar e ver falhar**
  Run: `cd backend && ./mvnw -B -q test -Dtest='ImportPreviewTest,ImportCommitTest'`
  Expected: os testes novos falham.
- [ ] **Step 3: Implementar** — no controller,
  `plan = aiSuggestions.apply(current.accountId(), previews.preview(...))`.
- [ ] **Step 4: Rodar a suíte do backend e regerar os tipos**
  - Run: `cd backend && ./mvnw -B verify` → BUILD SUCCESS.
  - Depois, com o backend rodando em `dev,e2e` (como no job `api-types`):
    `cd frontend && npm run api:types`.
  - Expected: o `schema.ts` ganha `suggestionSource` e `aiSuggestionsEnabled`.
- [ ] **Step 5: Commit** — `feat(imports): AI suggestions in the preview, outside the transaction`

### Task 6: frontend — selo, rodapé, interruptor e textos legais

**Files:**
- Modify: `frontend/src/features/imports/lib/review-state.ts` (+ `review-state.spec.ts`)
- Modify: `frontend/src/features/imports/components/ImportReviewRow.vue`,
  `ImportReviewStep.vue`
- Create: `frontend/src/features/settings/components/AiSuggestionsToggle.vue` (+ spec); usar em
  `CoupleSection.vue`
- Modify: `frontend/src/shared/api/queries/account.ts` (`useRenameAccount` → `useUpdateAccount`,
  que aceita `{ name?, aiSuggestionsEnabled? }`; atualizar `CoupleNameForm.vue`)
- Modify: `frontend/src/shared/i18n/locales/en.json`, `pt-BR.json`

**Interfaces:**
- Consumes: `schema.ts` regerado (Task 5).
- Produces:
  - `isAiSuggested(row: ReviewRow): boolean` = `row.suggestionSource === 'AI' && row.categoryId === row.suggestedCategoryId`;
  - `aiPendingCount(rows: ReviewRow[]): number` = linhas incluídas com `isAiSuggested`.

- [ ] **Step 1: Testes que falham**
  - `review-state.spec.ts`:
    - `isAiSuggested` é verdadeiro na carga, falso depois de `setCategory` para outra categoria e
      verdadeiro de novo ao voltar;
    - `aiPendingCount` ignora as linhas excluídas.
  - Spec de `ImportPage`/`ImportReviewStep` (msw): uma linha `AI` mostra o selo "Sugerido por IA";
    trocar a categoria o esconde; o rodapé mostra "1 sugestão por IA para conferir" e some com 0.
  - `AiSuggestionsToggle.spec.ts`:
    - reflete `aiSuggestionsEnabled` do `GET`;
    - ao clicar, envia `PATCH { aiSuggestionsEnabled: false }`;
    - mostra o texto explicativo;
    - em caso de erro, mostra a mensagem e volta o valor.
- [ ] **Step 2: Rodar e ver falhar**
  Run: `cd frontend && npx vitest run src/features/imports src/features/settings`
  Expected: os testes novos falham.
- [ ] **Step 3: Implementar.**
  - Componentes: selo com `Badge` + `tooltip` de `@/shared/ui`; interruptor com `switch` de
    `@/shared/ui`.
  - i18n em en e pt-BR:
    - `imports.review.aiBadge`: "AI suggestion" / "Sugerido por IA";
    - `imports.review.aiBadgeHint`: "Check before importing" / "Confira antes de importar";
    - `imports.review.aiPending` (plural): "{n} AI suggestion(s) to check" / "{n} sugestão(ões) por
      IA para conferir";
    - `settings.couple.aiSuggestions` e `settings.couple.aiSuggestionsHint`, com o texto da spec.
  - `legal.privacy.body` em en e pt-BR: os três acréscimos da spec, seção Privacidade (dados
    tratados, finalidades e base legal, destinatários) e a data atualizada.
- [ ] **Step 4: Rodar e ver passar**
  Run: `cd frontend && npm run lint && npm run test && npm run build`
  Expected: tudo verde.
- [ ] **Step 5: Commit** — `feat(imports): AI suggestion badge, review counter and settings toggle`

### Task 7: deploy e runbook

**Files:**
- Modify: `infra/.env.example`. Acrescentar, depois do bloco do Resend:
  `# Anthropic API key for AI category suggestions in imports. Empty disables them.` e
  `ANTHROPIC_API_KEY=`.
- Modify: `docs/runbook.md`, seção 5 (Segredos), com o passo a passo:
  - criar a chave em console.anthropic.com num workspace só para o Duo Finance;
  - definir um limite de gasto mensal de US$ 5;
  - colar a chave em `/opt/duo-finance/.env`;
  - `docker compose up -d api` aplica a mudança;
  - para desligar a IA em todo o sistema, deixar a variável vazia.
- Test: os testes `infra/tests` existentes (o `api` usa `env_file`, então o compose não muda).

- [ ] **Step 1: Editar os dois arquivos**
- [ ] **Step 2: Rodar os testes de infra leves**
  Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/code" -w /code --entrypoint bash bats/bats:latest -c 'apk add -q coreutils >/dev/null && bats --filter-tags "!docker" infra/tests/*.bats'`
  Expected: tudo `ok`, com os testes que exigem Docker pulados.
- [ ] **Step 3: Commit** — `docs(runbook): Anthropic API key for AI suggestions`
