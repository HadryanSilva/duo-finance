package br.dev.duofinance.recurring.service;

import br.dev.duofinance.account.service.AccountDataProbe;
import br.dev.duofinance.recurring.repository.RecurringRuleRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Any recurring rule counts as user data. */
@Component
public class RecurringRuleDataProbe implements AccountDataProbe {

    private final RecurringRuleRepository rules;

    public RecurringRuleDataProbe(RecurringRuleRepository rules) {
        this.rules = rules;
    }

    @Override
    public boolean hasUserData(UUID accountId) {
        return rules.existsByAccountId(accountId);
    }
}
