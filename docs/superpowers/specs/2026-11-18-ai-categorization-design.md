# Categorização automática de comerciantes novos — design

Data: 2026-11-18. Tamanho: projeto.

## Intenção

O MVP está em produção e o import da Revolut já lembra a categoria escolhida para cada descrição
(`category_memory`). O que ainda dá trabalho, segundo o usuário, são **os comerciantes novos que
aparecem todo mês**: nunca foram vistos, então a memória não tem o que sugerir, e eles chegam na
revisão como "Outros". O objetivo é que esses comerciantes já cheguem na revisão com uma categoria
plausível, para o casal só conferir.

## Objetivos

- No preview do import, linhas de compra com cartão sem memória recebem uma sugestão de categoria
  dada por IA, escolhida entre as categorias ativas do casal.
- Antes da IA, uma comparação flexível com a memória reaproveita escolhas de descrições parecidas
  ("pingo doce porto" usa a escolha de "pingo doce lisboa").
- Na revisão, a linha sugerida pela IA aparece marcada, para o casal saber o que conferir.
- A escolha final continua alimentando a memória como hoje, então a IA é consultada uma única vez por
  comerciante.
- Se a IA falhar, demorar ou não estiver configurada, o import funciona exatamente como hoje.

## Não-objetivos

- Lançamentos manuais (sem sugestão no formulário).
- Recategorizar lançamentos já gravados.
- Criar categorias novas a partir da IA.
- Aprender com edições feitas depois do import (a memória continua alimentada só pelo commit).
- Regras de palavra-chave definidas pelo usuário.
- Outros bancos.

## Anti-objetivos (seria um fracasso mesmo funcionando)

- Enviar à IA nome de pessoa, valor, data, IBAN ou qualquer dado do usuário além da descrição do
  comerciante e dos nomes das categorias.
- O import ficar bloqueado ou dar erro porque a IA está fora do ar.
- A sugestão da IA substituir uma escolha que o casal já fez (a memória sempre vence).
- Custo descontrolado: um arquivo enorme gerar dezenas de chamadas.

## Restrições

- Backend Java 25 / Spring Boot. SDK oficial `com.anthropic:anthropic-java` (3.2.0 no momento da
  escrita) com `AnthropicOkHttpClient`. Nada de HTTP cru nem shim compatível com OpenAI.
- Modelo: `claude-haiku-5-5` ($0,10 / $0,50 por milhão de tokens de entrada/saída). O usuário aceitou
  "um modelo pequeno e barato, como o Claude Haiku".
- GDPR: a política de privacidade precisa citar o novo destinatário (Anthropic, como subprocessador)
  e o que é enviado. A política está em `frontend/src/shared/i18n/locales/{en,pt-BR}.json`,
  chave `legal.privacy.body`.
- O preview roda hoje dentro de `@Transactional(readOnly = true)`. A chamada à IA não pode segurar
  uma conexão do banco por segundos.
- CI, e2e e desenvolvimento local rodam sem chave de API.
- Fluxo de branches: a branch sai da `develop` e o PR vai para a `develop`.

## Abordagens consideradas

1. **Escolhida: IA dentro do preview.** O backend junta as descrições desconhecidas e faz uma única
   chamada antes de devolver a revisão, com limite de tempo. As sugestões ficam guardadas em cache, e
   uma falha volta ao comportamento de hoje. Uma requisição só e mudança pequena no frontend; em troca,
   o upload fica alguns segundos mais lento.
2. **Chamada separada depois do preview.** A revisão aparece na hora e as sugestões vão chegando. Não
   acrescenta espera, mas exige mais frontend e cuidado com a corrida contra as escolhas do usuário.
   O usuário escolheu a abordagem 1.
3. **Dicionário de palavras-chave, sem IA.** Descartado pelo usuário: cobre pouco, exige manutenção e
   falha em comerciantes locais.
4. **Categorizar depois de gravar.** Descartado: a revisão é obrigatória antes de gravar, então a
   sugestão precisa estar pronta nela.

## Design

### Ordem da sugestão (por linha `INCOME`/`EXPENSE`)

Para os passos 1 e 2, uma entrada da memória que aponta para a **categoria padrão do tipo** ("Outros"
/ "Outras receitas") **conta como "não sei"**: ela é ignorada, como se não existisse. Decisão do
usuário: a memória de hoje está cheia de "Outros" que ninguém escolheu de fato, e isso esconderia
justamente os comerciantes que a feature quer resolver.

1. **Memória exata**, como hoje: `(description_key, kind)` com categoria ativa e diferente da padrão
   → origem `MEMORY`.
2. **Memória parecida:** só para linhas elegíveis para a IA (abaixo). Procura uma entrada da memória
   do mesmo `kind`, com categoria ativa e diferente da padrão, cujo `description_key` compartilha com
   o da linha o **maior prefixo de palavras inteiras, com pelo menos 2 palavras**. Empate: a mais
   recente (`updated_at`) → origem `SIMILAR`.
   - "pingo doce porto" ~ "pingo doce lisboa": casa.
   - "uber eats lisboa" ~ "uber": não casa, só 1 palavra em comum, para não confundir Uber com Uber
     Eats.
   - Minha decisão: limitar às linhas elegíveis (compras com cartão) evita que "transfer to joão" e
     "transfer to maria" casem pelo prefixo "transfer to". As entradas da memória podem vir de
     qualquer tipo de linha. Para não depender do `Type` delas, que não fica gravado, o prefixo
     comum não pode ser só um começo genérico da Revolut: os prefixos `transfer to`, `transfer from`,
     `payment from`, `cash withdrawal` e `to pocket` (e equivalentes localizados que o parser já
     conhece) nunca bastam sozinhos.
3. **IA**, só para linhas elegíveis que não tiveram 1 nem 2, e só se o casal não desligou a opção
   (ver "Opção nas configurações") → origem `AI`, se a IA devolver uma categoria válida diferente da
   padrão. Se a IA responder a categoria padrão, isso é tratado como `null`.
4. **Categoria padrão** ("Outros" / "Outras receitas"), se ativa → origem `DEFAULT`.
5. Nenhuma → origem `NONE`; o usuário precisa escolher, como hoje.

### Linhas elegíveis para a IA

- `kind = EXPENSE` e `Type` da Revolut = `CARD_PAYMENT`. Isso exclui `TRANSFER`, `TOPUP` e afins, que
  podem trazer nome de pessoa. As receitas ficam de fora porque quase nunca são pagamentos a
  comerciantes, e o que sobra nelas costuma ser nome de pessoa.
- Linhas com status `NEW` ou `POSSIBLE_DUPLICATE` (não as `ALREADY_IMPORTED`).
- Agrupadas por `description_key`: cada descrição distinta vai uma vez só.
- **Limite de 150 descrições distintas por preview.** Passando disso, as excedentes ficam na
  categoria padrão.

### O que é enviado

- Para cada descrição: um id curto (índice) e a **descrição original truncada em 100 caracteres**.
  Nada além disso: sem valor, data, conta ou usuário.
- A lista de categorias **de despesa ativas** do casal, **sem a categoria padrão**, cada uma com id
  curto (índice) e nome. Os UUIDs reais nunca saem do servidor.
- Instrução de sistema fixa, em inglês: classificar cada comerciante na categoria mais provável da
  lista, ou devolver `null` quando não houver confiança razoável. Não inventar categoria.

### Chamada

- `client.messages().create(...)` com `model = claude-haiku-5-5`.
- Saída estruturada via `output_config.format` com JSON schema: uma lista de
  `{ "item": int, "category": int | null }`.
- Esforço `low`; `max_tokens` dimensionado para 150 itens (~4000).
- **Limite de tempo total de 6 s** (timeout do cliente) e `maxRetries = 0`: no preview é melhor seguir
  sem sugestão do que fazer o usuário esperar mais.
- Resposta com `stop_reason` diferente de `end_turn`, JSON inválido, índice de item ou de categoria
  fora da lista, ou qualquer exceção do SDK: todas as linhas afetadas caem no passo 4. A falha é
  registrada em log como warning, sem as descrições.
- Escolha do builder: o código exato do SDK Java para saída estruturada. Ver `java/claude-api` da
  skill `claude-api` e o repositório do SDK; não adivinhar nomes de classes.

### Onde roda

- `ImportPreviewService.preview` continua transacional e devolve o plano com as origens `MEMORY`,
  `SIMILAR`, `DEFAULT` ou `NONE`, além da lista de linhas elegíveis sem sugestão.
- Um novo componente, `MerchantCategorizer` (pacote `imports.ai` ou similar), roda **fora da
  transação**: o controller chama o preview e depois o categorizador. Ele recebe as descrições e as
  categorias e devolve `Map<descriptionKey, categoryId>`. O planner aplica o resultado: as linhas
  elegíveis que receberam categoria passam a ter origem `AI`.
- Interface `MerchantCategorizer` com duas implementações:
  - `ClaudeMerchantCategorizer`, ativa quando a propriedade `duofinance.ai.anthropic-api-key` não está
    vazia;
  - `NoOpMerchantCategorizer`, que devolve um mapa vazio. É a padrão em dev, CI e e2e e é usada nos
    testes.
- O commit **não chama a IA**: ele replaneja só para validar as decisões, e as categorias vêm das
  decisões do usuário. O replanejamento do commit pula a etapa de IA.
- `PlannedRow` passa a carregar o `Type` da Revolut (canônico), necessário para a elegibilidade.

### Gravação da memória no commit (mudança decidida pelo usuário)

- Hoje o commit grava na memória a categoria de toda linha incluída.
- Passa a ser assim: quando a categoria decidida é a **categoria padrão do tipo**, a memória só é
  gravada se a sugestão daquela linha no replanejamento do commit (sem IA) **não** era a padrão. Ou
  seja, só quando o usuário escolheu "Outros" ativamente em vez de uma sugestão de memória.
- Como o commit não chama a IA, uma linha que no preview veio como `AI` e foi trocada para "Outros"
  aparece no replanejamento como `DEFAULT`/`NONE`, e a memória não é gravada. Consequência aceita
  pelo usuário: um comerciante que o casal quer mesmo em "Outros" volta a receber sugestão da IA a
  cada import, até o casal escolher outra categoria.
- Entradas antigas que apontam para a padrão ficam no banco e são ignoradas pela regra de "não sei".
  Não há migração de dados.

### Opção nas configurações (decidida pelo usuário)

- Migração `V9`: `ALTER TABLE accounts ADD COLUMN ai_suggestions_enabled boolean NOT NULL DEFAULT true`.
- `GET /api/v1/account` (`AccountResponse`) ganha `aiSuggestionsEnabled`.
- `PATCH /api/v1/account` (`UpdateAccountRequest`) aceita `aiSuggestionsEnabled` opcional. O `name`
  passa a ser opcional também, com pelo menos um dos dois obrigatório. Qualquer membro do casal pode
  alterar, como o nome.
- Frontend: em Configurações > Casal (`CoupleSection`), um interruptor "Sugestões de categoria por
  IA" com a explicação "Ao importar um extrato, descrições de compras com cartão de comerciantes
  novos são enviadas à Anthropic para sugerir uma categoria. Valores, datas e nomes não são
  enviados."
- Com a opção desligada, o preview pula o passo 3 e nada é enviado.

### Cache

- Em memória, no processo: chave `(accountId, description_key, hash da lista de categorias enviada)`
  → categoria escolhida ou "sem sugestão". TTL de 1 hora, no máximo 10.000 entradas.
- Serve para refazer o preview (troca do mapeamento de produtos, voltar e reenviar) sem chamar a IA
  de novo. Depois do commit, a memória assume.
- Nada da IA é gravado no banco.
- Escolha do builder: Caffeine (nova dependência) ou um mapa simples com expiração. Preferir Caffeine.

### API

- `PreviewResponse.PreviewRow` ganha `suggestionSource`: `MEMORY | SIMILAR | AI | DEFAULT | NONE`.
- Nenhum outro campo muda.
- Regerar `frontend/src/shared/api/schema.ts` (`npm run api:types`); o job `api-types` do CI confere.

### Frontend (revisão do import)

- Linha com `suggestionSource = AI`: selo discreto "Sugerido por IA" / "AI suggestion" ao lado do
  seletor de categoria, com tooltip "Confira antes de importar".
- O selo some quando o usuário troca a categoria da linha, inclusive via "aplicar às iguais".
- `SIMILAR` não mostra selo. É uma escolha anterior do próprio casal, como a memória.
- Rodapé da revisão: se houver linhas `AI`, mostrar o texto "N sugestões por IA para conferir".

### Configuração e deploy

- `application.yml`: `duofinance.ai.anthropic-api-key: ${ANTHROPIC_API_KEY:}`, além do modelo, do
  timeout e do limite de itens como propriedades com os valores acima por padrão.
- `infra/.env.example`: a linha `ANTHROPIC_API_KEY=` com um comentário.
- O compose de produção passa a variável para o container `api`; o teste `config.bats` que já checa
  o ambiente do `api` deve continuar passando.
- Runbook, seção 5 (Segredos): como criar a chave no Console da Anthropic, com limite de gasto mensal
  baixo (ex.: US$ 5), e onde colocá-la.

### Privacidade

- `legal.privacy.body` (en e pt-BR):
  - **"Dados que tratamos":** quando você importa um extrato, as descrições de compras com cartão de
    comerciantes que ainda não conhecemos são enviadas a um provedor de IA para sugerir uma categoria.
    Nunca enviamos valores, datas, nomes de pessoas ou dados da conta.
  - **"Com quem compartilhamos":** Anthropic PBC, como subprocessador, para sugerir categorias.
  - **"Finalidades e bases legais":** sugestão de categorias por IA com base em interesse legítimo
    (Art. 6(1)(f)), com direito de oposição exercido pelo interruptor nas Configurações.
- Atualizar a data da política.

## Testes

- **Planner / sugestão (unitários):**
  - ordem `MEMORY` > `SIMILAR` > `AI` > `DEFAULT` > `NONE`;
  - prefixo de 2 palavras casa e de 1 palavra não casa;
  - empate de `SIMILAR` resolvido pelo mais recente;
  - categoria arquivada não é sugerida em nenhuma etapa;
  - memória apontando para a categoria padrão é ignorada nos passos 1 e 2;
  - `SIMILAR` não se aplica a `TRANSFER` e não casa só por prefixo genérico ("transfer to");
  - resposta da IA igual à categoria padrão vira `null`;
  - só `CARD_PAYMENT` + `EXPENSE` é elegível;
  - `ALREADY_IMPORTED` não é elegível;
  - limite de 150 descrições distintas.
- **`ClaudeMerchantCategorizer` (unitário, com cliente falso ou servidor HTTP local):**
  - o corpo enviado contém só descrições truncadas e nomes de categorias, sem UUIDs nem valores;
  - índices inválidos são ignorados;
  - JSON inválido, timeout e erro HTTP devolvem um mapa vazio;
  - o cache evita a segunda chamada.
- **Integração do preview:**
  - com um categorizador falso devolvendo uma categoria, a linha sai com `suggestionSource = AI`;
  - com o no-op, o comportamento é idêntico ao de hoje;
  - o commit não chama o categorizador;
  - com `ai_suggestions_enabled = false`, o categorizador não é chamado;
  - commit com a categoria padrão numa linha sem sugestão de memória não grava memória; com a
    sugestão de memória trocada ativamente para a padrão, grava.
- **Conta:** `PATCH` com só `aiSuggestionsEnabled`, com só `name` e vazio (400).
- **Frontend:** selo aparece com `AI`, some ao trocar a categoria e o texto do rodapé conta as
  linhas `AI`; o interruptor nas Configurações salva e reflete o valor do `GET`.
- **E2E:** sem mudança (roda com o no-op).

## Decisões minhas (podem ser corrigidas)

- Modelo `claude-haiku-5-5`, esforço `low`, timeout de 6 s sem retry, limite de 150 descrições.
- Só despesas `CARD_PAYMENT` vão para a IA; receitas ficam de fora.
- Descrição truncada em 100 caracteres.
- Cache em memória por 1 hora; nada da IA é gravado no banco.
- `SIMILAR` restrito a compras com cartão e sem selo; lista de prefixos genéricos que nunca bastam.
- Selo só para `AI`, com contagem no rodapé.
- Opção ligada por padrão para casais novos e existentes.

## O que fica para o builder

- A forma exata da chamada no SDK Java e o texto do prompt de sistema (curto, em inglês).
- Nome e pacote exatos das classes novas, seguindo o padrão de pacotes do módulo `imports`.
- Como passar a etapa de IA ao planner sem quebrar a pureza dele: por exemplo, um segundo passe
  `ImportPlanner.applyAiSuggestions(plan, map)`.
