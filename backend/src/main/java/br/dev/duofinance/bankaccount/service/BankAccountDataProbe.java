package br.dev.duofinance.bankaccount.service;

import br.dev.duofinance.account.service.AccountDataProbe;
import br.dev.duofinance.bankaccount.repository.BankAccountRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Any bank account counts as user data. */
@Component
public class BankAccountDataProbe implements AccountDataProbe {

    private final BankAccountRepository bankAccounts;

    public BankAccountDataProbe(BankAccountRepository bankAccounts) {
        this.bankAccounts = bankAccounts;
    }

    @Override
    public boolean hasUserData(UUID accountId) {
        return bankAccounts.existsByAccountId(accountId);
    }
}
