# Aviso de dados desatualizados — design

## Intenção

Quase todos os lançamentos do casal entram pela importação do CSV da Revolut. Se ninguém importa por um tempo, o painel continua mostrando números que parecem certos, mas estão incompletos, e nada avisa. O aviso torna essa lacuna visível. Ninguém percebeu o problema no uso: ele surgiu de uma revisão proativa de melhorias, porque falha em silêncio.

## Decisões do usuário

- **O que se mede (opção A):** a data do **último lançamento importado** em cada conta bancária, e não a data da última importação. Importar hoje um CSV exportado há três semanas não pode parecer "em dia".
- **Limite:** o aviso aparece quando esse último lançamento tem **mais de 7 dias**.

## Abordagens consideradas

- **A. Último lançamento importado por conta** (escolhida): mostra a lacuna real e funciona com várias contas. Uma semana inteira sem compras geraria um falso aviso, o que é raro com uso diário do débito.
- **B. Data da última importação:** mais simples, mas mostra "em dia" depois de importar um arquivo antigo.

## Comportamento

- Entram só as contas bancárias **não arquivadas** escolhidas como conta de destino de alguma importação (`import_batches.bank_account_id`) e que ainda têm lançamentos daquela importação. Lançamentos de lotes desfeitos já foram apagados pelo undo. Contas que nunca receberam importação, como a de dinheiro, ficam de fora. Também ficam de fora as contas que só recebem linhas pelo mapeamento de produtos, como um cofre, onde quase não há movimento e o aviso seria um alarme falso.
- Para cada conta, `lastImportedDate` é a maior `date` entre os lançamentos importados que continuam na conta da importação. Um lançamento que alguém moveu para outra conta deixa de contar para as duas. A data de cada lançamento é limitada ao dia em que a importação foi feita (UTC), para que editar a data de um lançamento para frente não faça a importação parecer mais nova. Nas transferências importadas, vale a conta de origem (`bank_account_id`).
- O aviso aparece quando `hoje − lastImportedDate > 7 dias`. "Hoje" é a data local do navegador, porque o servidor roda em UTC e não tem fuso por conta.
- O aviso fica **no topo do painel**, acima do resumo do mês, e aparece em qualquer mês selecionado, porque fala do estado atual dos dados e não do mês exibido. Não tem botão de fechar: ele some quando uma importação nova traz lançamentos recentes.
- Um aviso por conta desatualizada; se houver mais de uma, uma linha para cada.

```
┌───────────────────────────────────────────────────────────────┐
│ ⚠ Revolut: lançamentos até 3 nov (há 18 dias). Importar extrato → │
└───────────────────────────────────────────────────────────────┘
  Resumo do mês …
```

O link leva a `/transactions/import`. A pré-seleção da conta é decisão do builder: só entra se a página de importação já aceitar isso com pouco esforço.

## Forma de construir

- **Backend:** um novo campo `lastImportedDate` (nulo quando nunca houve importação) em cada conta de `DashboardResponse.accounts`, lista que já traz só as contas não arquivadas. Ele vem de uma consulta agregada (`max(date)` agrupado por `bank_account_id`, onde `import_batch_id IS NOT NULL`), independente do mês exibido. O painel já é a consulta que a tela faz, então não é preciso um endpoint novo. Os tipos da API são regenerados.
- **Frontend:** o painel calcula quais contas passaram do limite e mostra o aviso com o componente de alerta existente. Textos em pt-BR e en; datas no formato curto já usado no app.

## Não-objetivos

- Nada de e-mail ou notificação: o aviso existe só no painel.
- Nenhum limite configurável pelo usuário.
- Nenhuma exigência de importação para contas manuais.

## Anti-objetivos

- O aviso não pode aparecer para contas que nunca foram importadas nem para contas arquivadas.
- O aviso não pode dizer "em dia" só porque houve uma importação recente de um arquivo antigo.

## Decisões deixadas para o builder

- O texto exato, o ícone e o estilo, dentro dos tokens e componentes existentes (o teste de guarda de design continua valendo).
- A pré-seleção da conta no link de importação, descrita acima.
