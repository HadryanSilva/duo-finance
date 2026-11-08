package br.dev.duofinance.account.service;

import br.dev.duofinance.account.repository.AccountRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountDeletionService {

    private final AccountRepository accounts;

    public AccountDeletionService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Deletes the account; database cascades remove its members, invitations, subscription and data. Flushes so the
     * cascades have run when this returns (e.g. before the former member joins another account).
     */
    @Transactional
    public void delete(UUID accountId) {
        accounts.findById(accountId).ifPresent(accounts::delete);
        accounts.flush();
    }
}
