# Branch `develop` e CI/CD — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** fazer a `develop` reunir as mudanças. O CI roda em PRs e em push na `develop` e na `main`,
o deploy só sai da `main`, e o ruleset passa a exigir os cinco checks nas duas branches.

**Architecture:** uma linha de trigger no `ci.yml` e um teste bats que fixa os triggers dos dois
workflows. O runbook documenta o fluxo. O ruleset `default` é alterado via `gh api` (configuração
do GitHub, fora do repositório).

**Tech Stack:** GitHub Actions, bats, actionlint, `gh` CLI.

**Spec:** `docs/superpowers/specs/2026-11-18-develop-branch-flow-design.md`

## Global Constraints

- Branch de trabalho: `chore/develop-branch-flow`, criada a partir de `origin/develop`. O PR é aberto
  contra a **`develop`**.
- Os nomes dos jobs do CI não mudam: `backend`, `frontend`, `api-types`, `e2e`, `infra`.
- `deploy.yml` não muda de comportamento: `workflow_run` do `CI`, `branches: [main]`, tags
  `:sha-<12>` e `:main`.
- Versão = PR `develop` → `main` com **merge commit** (nunca squash/rebase).
- Ruleset `default`, id `26937576`:
  - `include: ["refs/heads/main", "refs/heads/develop"]`.
  - Checks obrigatórios: os cinco jobs acima, com `strict_required_status_checks_policy: false`.
  - As regras `deletion`, `non_fast_forward` e `pull_request` continuam como estão.
  - A branch padrão continua `main`.
- Commits terminam com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- **`ci.yml` perder o push da `main`:** o `deploy.yml` depende de o CI rodar no push da `main` e
  pararia de deployar sem nenhum erro aparente. O teste da Task 1 exige `main` e `develop`.
- **`deploy.yml` passar a aceitar a `develop`:** isso faria deploy a cada merge, exatamente o que
  se quer evitar. O teste da Task 1 exige `branches: [main]` exato.
- **Integration ID errado nos checks do ruleset:** um check exigido que nunca é reportado trava
  todo PR. A Task 2 omite `integration_id` (qualquer fonte vale) e confere num PR real que os cinco
  checks aparecem e ficam verdes.
- **PUT do ruleset sobrescrever as regras existentes:** o PUT substitui a lista `rules` inteira.
  A Task 2 lê o JSON atual, altera só o necessário e compara antes e depois.
- **Squash na versão `develop` → `main`:** não há bloqueio técnico. A regra fica destacada no
  runbook (Task 1).

---

### Task 1: triggers dos workflows, teste e runbook

**Files:**
- Modify: `.github/workflows/ci.yml:1-14` (comentário do topo e `push.branches`)
- Modify: `.github/workflows/deploy.yml:1-3` (só o comentário do topo)
- Modify: `infra/tests/config.bats` (cabeçalho e novo teste no fim)
- Modify: `docs/runbook.md` (seções 8.4 e 8.5 e nova 8.6)

**Interfaces:**
- Produces: o PR desta branch contra a `develop`, que a Task 2 usa para conferir os checks.

- [ ] **Step 1: Escrever o teste que falha** no fim de `infra/tests/config.bats`. O teste não usa
  a tag `docker`, então roda no passo "light". Acrescentar uma linha ao comentário do topo:
  "- CI runs on pushes to main and develop; Deploy follows CI on main only."

```bash
@test "workflowTriggers_ciOnMainAndDevelop_deployOnMainOnly" {
  ci="$REPO_ROOT/.github/workflows/ci.yml"
  # push.branches of ci.yml, as written on its line
  run awk '/^  push:/ { p = 1; next } p && $1 == "branches:" { print; exit }' "$ci"
  echo "ci.yml push: [$output]"
  [[ "$output" == *main* ]]
  [[ "$output" == *develop* ]]
  # deploy.yml workflow_run.branches must be exactly [main]
  run awk '/^  workflow_run:/ { p = 1; next } p && $1 == "branches:" { print; exit }' "$DEPLOY_YML"
  echo "deploy.yml workflow_run: [$output]"
  [ "$(echo "$output" | tr -d ' ')" = "branches:[main]" ]
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `npx --yes bats@latest --filter workflowTriggers infra/tests/config.bats` (pelo Git Bash ou WSL)
Expected: FAIL. A saída mostra `ci.yml push: [    branches: [main]]`, sem `develop`.

- [ ] **Step 3: Implementar**
  - `ci.yml`: trocar para `branches: [main, develop]`. O comentário da linha 1–2 passa a dizer
    "they are the required status checks of the repository ruleset on main and develop".
  - `deploy.yml`: no comentário do topo, depois de "on a push to main", acrescentar uma frase:
    main only receives pull requests from develop (a release), so merges into develop never
    deploy.
  - `docs/runbook.md`:
    - **8.4:** renomear para "Proteção da `main` e da `develop`". Descrever o ruleset `default`
      (Settings > Rules > Rulesets): alvos `main` e `develop`, PR obrigatório, sem deleção e sem
      force-push, e os checks `backend`, `frontend`, `api-types`, `e2e` e `infra`. Manter a nota
      de que os checks só aparecem depois de rodar uma vez.
    - **8.5:** "faça merge de um pull request na `main`" passa a ser "faça merge de um pull request
      da `develop` na `main` (seção 8.6)".
    - **Nova 8.6, "Fluxo de branches":**
      - Branches saem da `develop`.
      - PRs de feature/fix vão para a `develop`, com qualquer método de merge. O CI roda, mas não
        há deploy.
      - Versão: PR `develop` → `main` com **Create a merge commit**. Squash ou rebase criam na
        `main` commits que a `develop` não tem, e as branches divergem.
      - Correção urgente segue o mesmo caminho.
      - Se a `main` divergir por engano, faça merge da `main` na `develop` por PR.

- [ ] **Step 4: Rodar e ver passar, e rodar o actionlint**

Run: `npx --yes bats@latest infra/tests/config.bats`
Expected: todos os testes PASS (o de `docker compose config` pode ser pulado se não houver Docker).
Run: `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.7`
Expected: nenhuma saída, exit 0. Sem Docker local, o job `infra` do PR cobre isso.

- [ ] **Step 5: Commit, push e PR contra a `develop`**

```bash
rtk git add .github/workflows/ci.yml .github/workflows/deploy.yml infra/tests/config.bats docs/runbook.md docs/superpowers/plans/2026-11-18-develop-branch-flow.md
rtk git commit -m "ci: run CI on develop pushes, deploy only from main; document the branch flow"
rtk git push -u origin chore/develop-branch-flow
gh pr create --base develop --title "ci: develop as integration branch" --body "..."
```

Expected: o PR mostra `develop ← chore/develop-branch-flow`, e os cinco jobs do CI disparam.

### Task 2: ruleset `default` cobrindo `main` e `develop` com checks obrigatórios

**Files:** nenhum no repositório. Trabalho em `<scratchpad>/ruleset-before.json` e
`<scratchpad>/ruleset-after.json`.

**Interfaces:**
- Consumes: o PR da Task 1, cujos checks precisam já ter rodado uma vez para existirem no GitHub.

- [ ] **Step 1: Salvar o estado atual**

Run: `gh api repos/hadryansilva/duo-finance/rulesets/26937576 > ruleset-before.json`
Expected: `conditions.ref_name.include == ["refs/heads/main"]` e `required_status_checks: []`.

- [ ] **Step 2: Montar o corpo do PUT** a partir do arquivo salvo, com `jq`.
  - Manter `name`, `target`, `enforcement`, `bypass_actors` e `rules`.
  - Definir `conditions.ref_name.include = ["refs/heads/main","refs/heads/develop"]`.
  - Na regra `required_status_checks`, `parameters.required_status_checks` passa a ser
    `[{"context":"backend"},{"context":"frontend"},{"context":"api-types"},{"context":"e2e"},{"context":"infra"}]`.
  - `strict_required_status_checks_policy: false`.
  - Remover os campos somente-leitura (`id`, `source`, `source_type`, `node_id`, `_links`,
    `created_at`, `updated_at`, `current_user_can_bypass`).

- [ ] **Step 3: Aplicar**

Run: `gh api -X PUT repos/hadryansilva/duo-finance/rulesets/26937576 --input ruleset-after.json`
Expected: HTTP 200.

- [ ] **Step 4: Conferir**

Run: `gh api repos/hadryansilva/duo-finance/rulesets/26937576 --jq '{inc:.conditions.ref_name.include, rules:[.rules[].type], checks:[.rules[]|select(.type=="required_status_checks")|.parameters.required_status_checks[].context]}'`
Expected:
- `inc` com `main` e `develop`.
- `rules` com `deletion`, `non_fast_forward`, `pull_request` e `required_status_checks`.
- `checks` com os cinco nomes.

Run: `gh pr checks <PR da Task 1>`
Expected: os cinco checks listados. Depois que terminarem, o PR fica mergeable só com todos verdes.

- [ ] **Step 5: Checagem final, depois que o usuário fizer o merge do PR na `develop`**
  - O merge é decisão do usuário. Não fazer merge por conta própria.
  - Run: `gh run list --branch develop --limit 3`
  - Expected: um run de `CI` com evento `push` na `develop` e **nenhum** run de `Deploy`.
