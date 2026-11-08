package br.dev.duofinance.bankaccount.service;

import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.bankaccount.dto.BankAccountRequest;
import br.dev.duofinance.bankaccount.dto.BankAccountResponse;
import br.dev.duofinance.bankaccount.model.BankAccount;
import br.dev.duofinance.bankaccount.repository.BankAccountBalanceQuery;
import br.dev.duofinance.bankaccount.repository.BankAccountRepository;
import br.dev.duofinance.recurring.repository.RecurringRuleRepository;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankAccountService {

    private final BankAccountRepository bankAccounts;
    private final BankAccountBalanceQuery balances;
    private final AccountMemberRepository members;
    private final RecurringRuleRepository rules;
    private final Clock clock;

    public BankAccountService(
            BankAccountRepository bankAccounts,
            BankAccountBalanceQuery balances,
            AccountMemberRepository members,
            RecurringRuleRepository rules,
            Clock clock) {
        this.bankAccounts = bankAccounts;
        this.balances = balances;
        this.members = members;
        this.rules = rules;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BankAccountResponse> list(UUID accountId, boolean includeArchived) {
        Map<UUID, Long> balanceById = balances.balances(accountId, today());
        return bankAccounts.list(accountId, includeArchived).stream()
                .map(b -> BankAccountResponse.of(b, balanceById.getOrDefault(b.getId(), b.getInitialBalanceCents())))
                .toList();
    }

    @Transactional(readOnly = true)
    public BankAccountResponse get(UUID accountId, UUID id) {
        return respond(requireOwned(accountId, id));
    }

    @Transactional
    public BankAccountResponse create(UUID accountId, BankAccountRequest request) {
        requireMember(accountId, request.holderUserId());
        BankAccount account = new BankAccount(
                accountId,
                request.name(),
                request.type(),
                request.initialBalanceCents(),
                request.holderUserId(),
                Instant.now(clock));
        return respond(bankAccounts.saveAndFlush(account));
    }

    @Transactional
    public BankAccountResponse update(UUID accountId, UUID id, BankAccountRequest request) {
        BankAccount account = requireOwned(accountId, id);
        requireMember(accountId, request.holderUserId());
        account.setName(request.name());
        account.setType(request.type());
        account.setInitialBalanceCents(request.initialBalanceCents());
        account.setHolderUserId(request.holderUserId());
        account.setUpdatedAt(Instant.now(clock));
        return respond(bankAccounts.saveAndFlush(account));
    }

    /** An active, not finished recurring rule using the account in any slot blocks archiving (IN_USE_BY_ACTIVE_RULE). */
    @Transactional
    public BankAccountResponse archive(UUID accountId, UUID id) {
        BankAccount account = requireOwned(accountId, id);
        if (!account.isArchived()) {
            if (rules.activeRuleUsesBankAccount(id)) {
                throw new ApiException(ErrorCode.IN_USE_BY_ACTIVE_RULE);
            }
            Instant now = Instant.now(clock);
            account.setArchivedAt(now);
            account.setUpdatedAt(now);
        }
        return respond(bankAccounts.saveAndFlush(account));
    }

    @Transactional
    public BankAccountResponse unarchive(UUID accountId, UUID id) {
        BankAccount account = requireOwned(accountId, id);
        if (account.isArchived()) {
            account.setArchivedAt(null);
            account.setUpdatedAt(Instant.now(clock));
        }
        return respond(bankAccounts.saveAndFlush(account));
    }

    /**
     * Deletes and flushes so a foreign-key violation surfaces here; the caller maps it to BANK_ACCOUNT_IN_USE after
     * this transaction has rolled back.
     */
    @Transactional
    public void delete(UUID accountId, UUID id) {
        bankAccounts.delete(requireOwned(accountId, id));
        bankAccounts.flush();
    }

    /** Bank account of the account, or 404. */
    @Transactional(readOnly = true)
    public BankAccount requireOwned(UUID accountId, UUID id) {
        return bankAccounts
                .findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** Bank account of the account that can receive new transactions: 404 if absent, BANK_ACCOUNT_ARCHIVED if archived. */
    @Transactional(readOnly = true)
    public BankAccount requireUsable(UUID accountId, UUID bankAccountId) {
        BankAccount account = requireOwned(accountId, bankAccountId);
        if (account.isArchived()) {
            throw new ApiException(ErrorCode.BANK_ACCOUNT_ARCHIVED);
        }
        return account;
    }

    private void requireMember(UUID accountId, UUID holderUserId) {
        if (holderUserId != null && !members.existsByAccountIdAndUserId(accountId, holderUserId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    private BankAccountResponse respond(BankAccount account) {
        long balance = balances.balances(account.getAccountId(), today())
                .getOrDefault(account.getId(), account.getInitialBalanceCents());
        return BankAccountResponse.of(account, balance);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
