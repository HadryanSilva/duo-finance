# Branch `develop` e adaptação do CI/CD — design

Data: 2026-11-18. Tamanho: mudança pequena.

## Intenção

Com o MVP em produção, cada merge na `main` gera um deploy, mesmo para mudanças pequenas. A `develop`
passa a reunir as mudanças. A `main` só recebe versões, e cada versão faz um único deploy. Não haverá
ambiente de homologação por enquanto.

## Objetivos

- Toda branch nova sai da `develop` e volta para ela por PR.
- A versão sai por um PR `develop` → `main`, e só o merge na `main` faz deploy.
- O CI roda nos PRs (qualquer base) e em push na `develop` e na `main`.
- Os checks obrigatórios valem de fato nas duas branches.

## Não-objetivos

- Ambiente de homologação/staging ou deploy a partir da `develop`.
- Imagens com tag `:develop`.
- Deploy por tag ou GitHub Release.

## Abordagens consideradas

1. **Escolhida:** o CI passa a rodar também em push na `develop`, e o `deploy.yml` continua igual
   (`workflow_run` do CI na `main`). Mexe pouco, e o PR `develop` → `main` funciona como o "release".
2. Deploy por tag/release: acrescenta um passo manual sem ganho em relação ao PR para a `main`.
3. CI só em PR, sem push na `develop`: deixa passar quebras que só aparecem quando duas branches se
   juntam na `develop`.

## Mudanças

- `.github/workflows/ci.yml`: `push.branches: [main, develop]`. O comentário do topo diz que os
  nomes dos jobs são os checks obrigatórios da `main` e da `develop`.
- `.github/workflows/deploy.yml`: sem mudança de comportamento. O comentário do topo explica que a
  `main` só recebe PRs da `develop`.
- `docs/runbook.md`:
  - Seção 8.4 passa a ser "Proteção da `main` e da `develop`" e descreve o ruleset como está
    configurado.
  - Seção 8.5: o primeiro deploy e os seguintes vêm do merge de um PR `develop` → `main`.
  - Nova subseção com o fluxo de branches:
    - Branches saem da `develop`.
    - PR de feature/fix → `develop` (o método de merge fica livre).
    - Versão: PR `develop` → `main`, sempre com **merge commit**. Squash ou rebase criam na `main`
      commits que a `develop` não tem, e as branches divergem.
    - Correção urgente também segue `develop` → `main`.
- Configuração do GitHub, aplicada via `gh api` (não versionada):
  - Ruleset `default` (id 26937576): incluir `refs/heads/develop` além de `refs/heads/main`.
  - Preencher `required_status_checks` com `backend`, `frontend`, `api-types`, `e2e` e `infra`.
    Hoje a lista está vazia, então um PR pode entrar com o CI vermelho.
  - Mantêm-se as regras já existentes: PR obrigatório, sem deleção e sem force-push.

## Decisões minhas (podem ser corrigidas)

- Não restringir o método de merge no ruleset. Um único ruleset cobre as duas branches, e restringir
  a `main` a merge commit exigiria um segundo ruleset. A regra fica documentada no runbook. Se você
  preferir que o GitHub obrigue, separo em dois rulesets.
- A branch padrão do repositório continua sendo a `main`. Mudar para `develop` faria os PRs abrirem
  contra a `develop` por padrão, mas alteraria o que o visitante vê no repositório. Posso mudar se
  você quiser.
- Os checks obrigatórios não exigem "branch atualizada" (strict = false), para não obrigar rebase a
  cada merge na `develop`.

## Verificação

- `actionlint` (o job `infra` já roda) passa.
- O PR desta mudança, aberto contra a `develop`, mostra os cinco checks como obrigatórios.
- Depois do merge, o CI roda no push da `develop` e o `Deploy` não dispara.
