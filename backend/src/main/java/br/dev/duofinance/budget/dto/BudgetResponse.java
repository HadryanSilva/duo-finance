package br.dev.duofinance.budget.dto;

import br.dev.duofinance.budget.model.Budget;
import br.dev.duofinance.transaction.dto.TransactionResponse.CategoryRef;
import java.time.YearMonth;
import java.util.UUID;

public record BudgetResponse(
        UUID id,
        CategoryRef category,
        long monthlyLimitCents,
        YearMonth month,
        long spentCents,
        int percent,
        BudgetStatus status) {

    public static BudgetResponse of(Budget budget, CategoryRef category, YearMonth month, long spentCents) {
        int percent = percent(spentCents, budget.getMonthlyLimitCents());
        return new BudgetResponse(
                budget.getId(),
                category,
                budget.getMonthlyLimitCents(),
                month,
                spentCents,
                percent,
                BudgetStatus.of(percent));
    }

    /** floor(spent * 100 / limit) in long arithmetic, capped to the int range. */
    static int percent(long spentCents, long limitCents) {
        long percent;
        try {
            percent = Math.multiplyExact(spentCents, 100L) / limitCents;
        } catch (ArithmeticException e) {
            percent = Integer.MAX_VALUE;
        }
        return (int) Math.min(percent, Integer.MAX_VALUE);
    }
}
