# Importação de extrato CSV da Revolut: design

Data: 2026-11-10. Base: MVP em produção (spec `2026-11-05-duo-finance-mvp-design.md`), migrations V1–V4 congeladas.

## 1. Objetivo

Reduzir a digitação manual: o casal exporta o extrato CSV da conta pessoal da Revolut, sobe o arquivo, revisa e confirma, e os lançamentos aparecem no painel com categoria, sem duplicatas. A primeira importação pede categorizar; as seguintes quase não pedem, porque o sistema lembra a categoria escolhida para cada descrição.

Critérios de sucesso:

- importar vários meses de uma vez e repetir a importação com períodos sobrepostos sem criar duplicatas;
- linhas parecidas com lançamentos digitados à mão aparecem sinalizadas antes de gravar;
- uma importação inteira pode ser desfeita.

## 2. Decisões

| Tema | Decisão |
| --- | --- |
| Formato | Só Revolut (CSV da conta pessoal), com parser dedicado. Outros bancos serão parsers novos no mesmo encaixe. |
| Categorias | Tela de revisão obrigatória antes de gravar, com memória de categoria por descrição. |
| Duplicatas | Linhas já importadas (mesma impressão digital) são puladas. Linhas parecidas com lançamento sem impressão digital (mesma data, valor e conta bancária) aparecem como possível duplicata, desmarcadas por padrão. |
| Abordagem | O backend lê o arquivo. Preview sem estado e commit que reenvia o mesmo arquivo. O arquivo nunca é armazenado. |
| Desfazer | Cada importação é um lote; desfazer apaga os lançamentos do lote. |

## 3. Escopo

**Dentro:** parser Revolut, regras de importação (seção 5), preview, commit, memória de categorias, histórico de lotes, desfazer, assistente no frontend, textos em pt-BR e en.

**Fora:** outros bancos; multi-moeda e conversão; criação automática de categorias; edição de valor ou data na revisão (edita-se o lançamento depois); regras de categorização por palavra-chave definidas pelo usuário; importação agendada ou automática; armazenamento do arquivo enviado.

## 4. Modelo de dados (migration V5)

**`import_batches`**

- `id uuid PK`, `account_id uuid NOT NULL → accounts ON DELETE CASCADE`, `source varchar(20) NOT NULL` (`REVOLUT`), `bank_account_id uuid → bank_accounts` (conta escolhida no upload), `created_by uuid → users ON DELETE SET NULL`, `row_count int NOT NULL` (lançamentos criados), `undone_at timestamptz`, `created_at timestamptz NOT NULL`.
- Índice em `(account_id, created_at DESC)`.

**`transactions`** ganha duas colunas opcionais:

- `import_batch_id uuid → import_batches ON DELETE SET NULL`;
- `import_fingerprint varchar(64)`, com índice único parcial `(account_id, import_fingerprint) WHERE import_fingerprint IS NOT NULL`. É esse índice que impede a reimportação, inclusive entre dois commits simultâneos.

**`category_memory`**

- `account_id uuid → accounts ON DELETE CASCADE`, `description_key varchar(140) NOT NULL`, `kind varchar(10) NOT NULL` (`INCOME` | `EXPENSE`), `category_id uuid NOT NULL → categories ON DELETE CASCADE`, `updated_at timestamptz NOT NULL`.
- PK em `(account_id, description_key, kind)`.

**`description_key`:** descrição em minúsculas, sem acentos (NFD sem marcas), espaços colapsados e sem sequências finais de dígitos, `*` ou `#` (números de cartão, referências).

**Impressão digital:** SHA-256 hexadecimal de `REVOLUT|<Started Date com hora>|<Amount>|<Currency>|<Description>|<Product>`, com os campos como estão no arquivo (sem a descrição cortada). `Started Date` não muda quando a linha passa de PENDING para COMPLETED, então uma linha pulada hoje como PENDING gera a mesma impressão digital quando for importada depois. A tarifa separada (seção 5) usa a impressão digital da linha com o sufixo `:fee` antes do hash. Um TRANSFER formado por duas linhas usa a impressão digital da ponta de saída (valor negativo).

## 5. Leitura e regras da Revolut

**Parser (`RevolutCsvParser`)**: só transforma bytes em linhas normalizadas.

- Cabeçalho obrigatório com as colunas `Type, Product, Started Date, Completed Date, Description, Amount, Fee, Currency, State, Balance`, em qualquer ordem; colunas extras são ignoradas. Faltando alguma: `IMPORT_FORMAT_UNSUPPORTED`.
- UTF-8, com ou sem BOM; separador vírgula; campos entre aspas conforme RFC 4180.
- Datas `yyyy-MM-dd HH:mm:ss`; valores com ponto decimal, convertidos para centavos com `BigDecimal` (sem ponto flutuante). Linha com data ou valor ilegível: `IMPORT_ROW_INVALID`, indicando o número da linha.
- No máximo 5.000 linhas de dados (`IMPORT_TOO_MANY_ROWS`). O nginx já limita a requisição a 1 MB.

**Planejamento (`ImportPlanner`)**: aplica as regras e não toca no banco; recebe as linhas, o mapeamento de produtos, a moeda da conta, as impressões digitais existentes, a memória de categorias e os candidatos a possível duplicata.

| Situação | Resultado |
| --- | --- |
| `State = COMPLETED` | importável |
| `State = PENDING` | pulada (`PENDING`); entra numa importação futura, quando estiver COMPLETED |
| `State = REVERTED` ou `DECLINED` | pulada (`REVERTED`) |
| `Currency` diferente da moeda da conta | pulada (`OTHER_CURRENCY`) |
| `Type = EXCHANGE` | pulada (`EXCHANGE`) |
| `Product` mapeado para "ignorar" | pulada (`PRODUCT_IGNORED`) |
| `Amount < 0` | `EXPENSE` com valor absoluto |
| `Amount > 0` | `INCOME` |
| `Amount = 0` | pulada (`ZERO_AMOUNT`) |
| `Fee > 0` | linha extra `EXPENSE` com a tarifa, descrição "Tarifa Revolut" / "Revolut fee" (no idioma de quem importa), mesma data e conta |
| Data do lançamento | dia do `Completed Date` |
| Descrição | `Description` cortada em 140 caracteres |

**Produtos e transferências**

- O preview lista os valores distintos de `Product` do arquivo. O produto `Current` é mapeado por padrão para a conta bancária escolhida no upload; os demais começam como "ignorar" e o usuário pode mapeá-los para outra conta bancária do Duo Finance.
- Duas linhas importáveis de produtos mapeados para contas bancárias diferentes, com o mesmo `Started Date` e valores opostos (mesmo valor absoluto), viram um único `TRANSFER` da conta da ponta negativa para a da ponta positiva. Linha sem par vira receita ou despesa normal.

**Status de cada linha planejada**

- `NEW`: importável e sem conflito.
- `ALREADY_IMPORTED`: a impressão digital já existe na conta; não pode ser incluída.
- `POSSIBLE_DUPLICATE`: existe lançamento sem impressão digital com a mesma data, o mesmo valor, o mesmo tipo e a mesma conta bancária; começa desmarcada, mas pode ser incluída.

**Categoria sugerida** (só `INCOME` e `EXPENSE`): a da memória para `(description_key, kind)`, se ainda estiver ativa; senão, a categoria padrão do sistema do tipo correspondente ("Outros" / "Outras receitas"), se ativa; senão, nenhuma, e o usuário precisa escolher.

## 6. API

Tudo sob `/api/v1/imports`, com as mesmas verificações das rotas financeiras: usuário membro da conta e assinatura com acesso (`402` caso contrário).

**`POST /revolut/preview`** (multipart): `file`, `bankAccountId`, `productMapping` opcional (JSON `{ "<Product>": "<bankAccountId>" | null }`, `null` = ignorar). Não grava nada. Resposta:

- `products`: produtos encontrados e o mapeamento aplicado;
- `rows`: `rowId` (impressão digital), `date`, `kind`, `amountCents`, `description`, `bankAccountId`, `destinationBankAccountId` (TRANSFER), `suggestedCategoryId`, `status`;
- `skipped`: contagem por motivo (`PENDING`, `REVERTED`, `OTHER_CURRENCY`, `EXCHANGE`, `PRODUCT_IGNORED`, `ZERO_AMOUNT`) e, para cada linha pulada, data, descrição, valor original e motivo.

**`POST /revolut/commit`** (multipart): o mesmo `file`, `bankAccountId` e `productMapping` do preview, e `decisions` (JSON `[{ "rowId", "include", "categoryId" }]`). O backend lê e planeja o arquivo de novo e aplica as decisões às linhas recalculadas: valores, datas e tipos vêm sempre do arquivo. Numa única transação: cria o lote, insere os lançamentos incluídos com `import_batch_id` e `import_fingerprint` (`ON CONFLICT DO NOTHING` no índice da impressão digital) e grava ou atualiza `category_memory` com a categoria escolhida para cada `(description_key, kind)`. Linhas `NEW` sem decisão não entram. Resposta: `{ batchId, created, skippedDuplicates }`. Se nada for criado, o lote não é gravado e `batchId` é `null`.

**`GET /`**: lotes da conta, mais recentes primeiro, paginado: `id`, `source`, `bankAccountId`, `createdBy` (nome, ou "Ex-membro"), `createdAt`, `rowCount`, `undoneAt`.

**`POST /{batchId}/undo`**: apaga os lançamentos da conta que ainda têm aquele `import_batch_id`, inclusive os editados depois da importação, e marca `undone_at`. Qualquer membro da conta pode desfazer. Lote já desfeito: `409 IMPORT_ALREADY_UNDONE`. A memória de categorias não é revertida.

**Erros**

| Código | Quando |
| --- | --- |
| `413` | requisição acima de 1 MB (nginx) ou do limite multipart do Spring, que deve ser igual |
| `422 IMPORT_FORMAT_UNSUPPORTED` | cabeçalho sem as colunas da Revolut |
| `422 IMPORT_ROW_INVALID` | data ou valor ilegível; indica a linha |
| `422 IMPORT_TOO_MANY_ROWS` | mais de 5.000 linhas |
| `422 VALIDATION_FAILED` | `bankAccountId` ou conta do mapeamento arquivada ou de outra conta; categoria inválida, arquivada ou de tipo diferente do lançamento; `rowId` inexistente no arquivo; linha `ALREADY_IMPORTED` com `include = true`; receita ou despesa incluída sem categoria; `categoryId` enviado para um TRANSFER. Cada erro indica o `rowId` |
| `404` | lote inexistente ou de outra conta (undo) |
| `409 IMPORT_ALREADY_UNDONE` | undo de lote já desfeito |

Um conflito de impressão digital no commit (outro commit gravou a mesma linha antes) não é erro: a linha só entra em `skippedDuplicates`.

## 7. Backend

Novo módulo `br.dev.duofinance.imports`, com a mesma estrutura dos demais (`controller`, `dto`, `model`, `repository`, `service`):

- `RevolutCsvParser`: bytes → linhas normalizadas; sem dependência do Spring.
- `ImportPlanner`: regras da seção 5, função pura; testável sem banco.
- `DescriptionKeys`: normalização da `description_key`.
- `ImportPreviewService`: carrega impressões digitais, memória e candidatos a duplicata da conta e chama o planner.
- `ImportCommitService`: replaneja, valida as decisões, grava lote, lançamentos e memória em uma transação.
- `ImportBatchService`: listagem e desfazer.

A gravação dos lançamentos reaproveita as validações de referência existentes (`TransactionReferenceValidator`) para conta bancária e categoria.

## 8. Frontend

Novo `features/imports`, com rota `/transactions/import`, acessada por um botão "Importar extrato" na página de lançamentos. Assistente em três passos:

1. **Arquivo:** conta bancária de destino, arquivo `.csv` e o link "Como exportar da Revolut". Depois do primeiro preview, se o arquivo tiver outros produtos, aparece o mapeamento produto → conta ou "ignorar", e mudar o mapeamento refaz o preview.
2. **Revisão:** abas **Novas**, **Possíveis duplicatas** e **Puladas** (somente leitura, com o motivo). Cada linha tem "incluir" e seletor de categoria (TRANSFER não tem categoria). Linhas agrupadas por descrição: ao mudar a categoria de uma linha, a tela oferece aplicar às outras linhas com a mesma descrição e o mesmo tipo. Rodapé com a quantidade de linhas incluídas e os totais de receita e despesa. "Importar" fica desativado enquanto houver linha incluída sem categoria. O arquivo fica em memória no navegador para o commit.
3. **Resultado:** "X lançamentos importados, Y já existiam", com "Ver lançamentos" e "Desfazer importação". Desfazer pede confirmação e avisa que edições feitas depois da importação também serão perdidas.

Abaixo do assistente, o histórico de importações, com desfazer em cada lote ainda ativo. Textos em pt-BR e en; valores na moeda da conta com o formatador existente. Depois de commit ou undo, as consultas de lançamentos, dashboard e orçamentos são invalidadas.

## 9. Testes

**Backend, unidade**

- Parser: arquivo de exemplo anônimo; cabeçalho fora de ordem e com colunas extras; coluna faltando; BOM; campos entre aspas com vírgula; data e valor inválidos; limite de linhas.
- Planner: uma regra por teste (cada `State`, outra moeda, EXCHANGE, `Amount = 0`, tarifa separada, produto ignorado, transferência com e sem par, status `ALREADY_IMPORTED` e `POSSIBLE_DUPLICATE`, sugestão pela memória, pela categoria padrão e sem sugestão).
- Impressão digital igual para a mesma linha em PENDING e em COMPLETED; diferente para a tarifa.
- `DescriptionKeys`: acentos, caixa, espaços, sufixos numéricos.

**Backend, integração** (mesma infraestrutura dos testes de lançamento)

- preview não grava nada;
- commit cria lote, lançamentos com impressão digital e memória;
- reimportar o mesmo arquivo cria 0 lançamentos;
- dois commits simultâneos do mesmo arquivo não geram duplicata;
- possível duplicata desmarcada não entra, marcada entra;
- valores do commit vêm do arquivo, não das decisões;
- desfazer apaga só os lançamentos do lote, inclusive editados, e um segundo desfazer responde `409`;
- conta bancária ou categoria de outra conta, arquivada ou de tipo errado: `422`;
- assinatura sem acesso: `402`;
- o outro membro da conta compartilhada lista e desfaz o lote.

**Frontend** (componente, com msw): três passos; mapeamento de produtos refaz o preview; agrupamento e "aplicar às iguais"; botão desativado sem categoria; abas; erros de formato, de linha inválida e de tamanho; confirmação do desfazer; histórico.

**E2E:** importar o arquivo de exemplo, ver os lançamentos na lista, reimportar e ver "já existiam", desfazer e ver a lista vazia de novo.
