package br.dev.duofinance.transaction.service;

import br.dev.duofinance.bankaccount.service.BankAccountService;
import br.dev.duofinance.category.service.CategoryService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.web.PageRequests;
import br.dev.duofinance.shared.web.PageResponse;
import br.dev.duofinance.transaction.dto.TransactionFilter;
import br.dev.duofinance.transaction.dto.TransactionResponse;
import br.dev.duofinance.transaction.dto.TransactionResponseAssembler;
import br.dev.duofinance.transaction.dto.TransactionTotalsResponse;
import br.dev.duofinance.transaction.model.Transaction;
import br.dev.duofinance.transaction.model.TransactionKind;
import br.dev.duofinance.transaction.repository.TransactionRepository;
import br.dev.duofinance.transaction.repository.TransactionSpecifications;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionQueryService {

    static final int MAX_QUERY_LENGTH = 100;
    private static final Sort ORDER =
            Sort.by(Sort.Order.desc("date"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final TransactionRepository transactions;
    private final TransactionResponseAssembler assembler;
    private final CategoryService categories;
    private final BankAccountService bankAccounts;
    private final EntityManager entityManager;

    public TransactionQueryService(
            TransactionRepository transactions,
            TransactionResponseAssembler assembler,
            CategoryService categories,
            BankAccountService bankAccounts,
            EntityManager entityManager) {
        this.transactions = transactions;
        this.assembler = assembler;
        this.categories = categories;
        this.bankAccounts = bankAccounts;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> list(UUID accountId, TransactionFilter filter, int page, int size) {
        Specification<Transaction> spec = specFor(accountId, filter);
        Page<Transaction> result = transactions.findAll(spec, PageRequests.of(page, size, ORDER));
        List<TransactionResponse> content = assembler.toResponses(accountId, result.getContent());
        return new PageResponse<>(
                content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /**
     * Sums of the rows {@link #list} would return for the same filter, in one aggregate query. With a bank-account
     * filter, a transfer into that account counts as income and one out of it as expense; otherwise transfers only
     * add to the count.
     */
    @Transactional(readOnly = true)
    public TransactionTotalsResponse totals(UUID accountId, TransactionFilter filter) {
        Specification<Transaction> spec = specFor(accountId, filter);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        Root<Transaction> root = query.from(Transaction.class);
        Expression<Long> amount = root.get("amountCents");
        Expression<Long> zero = cb.literal(0L);

        Predicate income = cb.equal(root.get("kind"), TransactionKind.INCOME);
        Predicate expense = cb.equal(root.get("kind"), TransactionKind.EXPENSE);
        UUID bank = filter.bankAccountId();
        if (bank != null) {
            Predicate transfer = cb.equal(root.get("kind"), TransactionKind.TRANSFER);
            income = cb.or(income, cb.and(transfer, cb.equal(root.get("destinationBankAccountId"), bank)));
            expense = cb.or(expense, cb.and(transfer, cb.equal(root.get("bankAccountId"), bank)));
        }

        query.multiselect(
                        cb.coalesce(
                                cb.sum(cb.<Long>selectCase()
                                        .when(income, amount)
                                        .otherwise(zero)),
                                zero),
                        cb.coalesce(
                                cb.sum(cb.<Long>selectCase()
                                        .when(expense, amount)
                                        .otherwise(zero)),
                                zero),
                        cb.count(root))
                .where(spec.toPredicate(root, query, cb));
        Tuple row = entityManager.createQuery(query).getSingleResult();
        long incomeCents = row.get(0, Long.class);
        long expenseCents = row.get(1, Long.class);
        return new TransactionTotalsResponse(
                incomeCents, expenseCents, incomeCents - expenseCents, row.get(2, Long.class));
    }

    /** Validation, ownership checks and predicates shared by {@link #list} and {@link #totals}. */
    private Specification<Transaction> specFor(UUID accountId, TransactionFilter filter) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw ApiException.invalidField("from", "OutOfRange");
        }
        String q = filter.q() == null ? "" : filter.q().trim();
        if (q.length() > MAX_QUERY_LENGTH) {
            throw ApiException.invalidField("q", "Size");
        }

        Specification<Transaction> spec = TransactionSpecifications.inAccount(accountId);
        if (filter.from() != null) {
            spec = spec.and(TransactionSpecifications.dateFrom(filter.from()));
        }
        if (filter.to() != null) {
            spec = spec.and(TransactionSpecifications.dateTo(filter.to()));
        }
        if (filter.kind() != null) {
            spec = spec.and(TransactionSpecifications.ofKind(filter.kind()));
        }
        if (filter.categoryId() != null) {
            categories.requireOwned(accountId, filter.categoryId());
            spec = spec.and(TransactionSpecifications.inCategory(filter.categoryId()));
        }
        if (filter.bankAccountId() != null) {
            bankAccounts.requireOwned(accountId, filter.bankAccountId());
            spec = spec.and(TransactionSpecifications.involvingBankAccount(filter.bankAccountId()));
        }
        if (filter.createdBy() != null) {
            spec = spec.and(TransactionSpecifications.createdBy(filter.createdBy()));
        }
        if (!q.isEmpty()) {
            spec = spec.and(TransactionSpecifications.descriptionContains(q));
        }

        return spec;
    }
}
