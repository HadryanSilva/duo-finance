package br.dev.duofinance.transaction.service;

import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.finance.FinanceValidation;
import br.dev.duofinance.transaction.dto.TransactionRequest;
import br.dev.duofinance.transaction.dto.TransactionResponse;
import br.dev.duofinance.transaction.dto.TransactionResponseAssembler;
import br.dev.duofinance.transaction.model.Transaction;
import br.dev.duofinance.transaction.repository.TransactionRepository;
import br.dev.duofinance.transaction.service.TransactionReferenceValidator.ReferenceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes flush before returning so constraint violations surface here; the controller maps them after rollback. */
@Service
public class TransactionService {

    private final TransactionRepository transactions;
    private final TransactionReferenceValidator validator;
    private final TransactionResponseAssembler assembler;
    private final Clock clock;

    public TransactionService(
            TransactionRepository transactions,
            TransactionReferenceValidator validator,
            TransactionResponseAssembler assembler,
            Clock clock) {
        this.transactions = transactions;
        this.validator = validator;
        this.assembler = assembler;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID accountId, UUID id) {
        return respond(accountId, requireOwned(accountId, id));
    }

    @Transactional
    public TransactionResponse create(UUID accountId, UUID userId, TransactionRequest request) {
        validate(accountId, request, ReferenceSnapshot.none());
        Instant now = Instant.now(clock);
        Transaction transaction = new Transaction(accountId, userId, now);
        apply(transaction, request, now);
        return respond(accountId, transactions.saveAndFlush(transaction));
    }

    @Transactional
    public TransactionResponse update(UUID accountId, UUID id, TransactionRequest request) {
        Transaction transaction = requireOwned(accountId, id);
        ReferenceSnapshot current = new ReferenceSnapshot(
                transaction.getCategoryId(), transaction.getBankAccountId(), transaction.getDestinationBankAccountId());
        validate(accountId, request, current);
        apply(transaction, request, Instant.now(clock));
        return respond(accountId, transactions.saveAndFlush(transaction));
    }

    @Transactional
    public void delete(UUID accountId, UUID id) {
        transactions.delete(requireOwned(accountId, id));
        transactions.flush();
    }

    private Transaction requireOwned(UUID accountId, UUID id) {
        return transactions
                .findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private void validate(UUID accountId, TransactionRequest request, ReferenceSnapshot current) {
        LocalDate date = request.date();
        if (date.isBefore(FinanceValidation.MIN_DATE) || date.isAfter(FinanceValidation.MAX_DATE)) {
            throw ApiException.invalidField("date", "OutOfRange");
        }
        validator.validate(
                accountId,
                request.kind(),
                request.categoryId(),
                request.bankAccountId(),
                request.destinationBankAccountId(),
                current);
    }

    private static void apply(Transaction transaction, TransactionRequest request, Instant now) {
        transaction.assign(
                request.kind(),
                request.amountCents(),
                request.date(),
                request.description(),
                request.categoryId(),
                request.bankAccountId(),
                request.destinationBankAccountId(),
                now);
    }

    private TransactionResponse respond(UUID accountId, Transaction transaction) {
        return assembler.toResponses(accountId, List.of(transaction)).get(0);
    }
}
