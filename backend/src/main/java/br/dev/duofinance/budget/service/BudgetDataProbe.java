package br.dev.duofinance.budget.service;

import br.dev.duofinance.account.service.AccountDataProbe;
import br.dev.duofinance.budget.repository.BudgetRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Any budget counts as user data. */
@Component
public class BudgetDataProbe implements AccountDataProbe {

    private final BudgetRepository budgets;

    public BudgetDataProbe(BudgetRepository budgets) {
        this.budgets = budgets;
    }

    @Override
    public boolean hasUserData(UUID accountId) {
        return budgets.existsByAccountId(accountId);
    }
}
