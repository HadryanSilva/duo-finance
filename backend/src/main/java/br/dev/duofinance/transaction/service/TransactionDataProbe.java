package br.dev.duofinance.transaction.service;

import br.dev.duofinance.account.service.AccountDataProbe;
import br.dev.duofinance.transaction.repository.TransactionRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Any transaction counts as user data. */
@Component
public class TransactionDataProbe implements AccountDataProbe {

    private final TransactionRepository transactions;

    public TransactionDataProbe(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Override
    public boolean hasUserData(UUID accountId) {
        return transactions.existsByAccountId(accountId);
    }
}
