package br.com.hadryan.duo.finance.budget.event;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Publicado pelo BudgetService quando o gasto de uma categoria excede o valor alocado.
 * Consumido pelo NotificationListener para persistir notificação in-app.
 */
public record BudgetExceededEvent(
        UUID coupleId,
        TransactionCategory category,
        BigDecimal allocated,
        BigDecimal spent
) {}