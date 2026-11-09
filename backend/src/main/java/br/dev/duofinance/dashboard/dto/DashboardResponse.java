package br.dev.duofinance.dashboard.dto;

import br.dev.duofinance.bankaccount.model.BankAccountType;
import br.dev.duofinance.budget.dto.BudgetResponse;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record DashboardResponse(
        YearMonth month,
        String currency,
        LocalDate balancesAsOf,
        long totalBalanceCents,
        List<AccountBalance> accounts,
        long incomeCents,
        long expenseCents,
        List<CategorySpend> spendingByCategory,
        List<BudgetResponse> budgets,
        List<UpcomingOccurrence> upcoming) {

    public record AccountBalance(UUID id, String name, BankAccountType type, long balanceCents) {}

    public record CategorySpend(UUID categoryId, String name, String color, String icon, long totalCents) {}

    public record UpcomingOccurrence(
            UUID ruleId,
            LocalDate date,
            TransactionKind kind,
            long amountCents,
            @Nullable String description,
            @Nullable UUID categoryId) {}
}
