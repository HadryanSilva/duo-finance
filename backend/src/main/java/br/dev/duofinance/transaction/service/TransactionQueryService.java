package br.dev.duofinance.transaction.service;

import br.dev.duofinance.bankaccount.service.BankAccountService;
import br.dev.duofinance.category.service.CategoryService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.web.PageRequests;
import br.dev.duofinance.shared.web.PageResponse;
import br.dev.duofinance.transaction.dto.TransactionFilter;
import br.dev.duofinance.transaction.dto.TransactionResponse;
import br.dev.duofinance.transaction.dto.TransactionResponseAssembler;
import br.dev.duofinance.transaction.model.Transaction;
import br.dev.duofinance.transaction.repository.TransactionRepository;
import br.dev.duofinance.transaction.repository.TransactionSpecifications;
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

    public TransactionQueryService(
            TransactionRepository transactions,
            TransactionResponseAssembler assembler,
            CategoryService categories,
            BankAccountService bankAccounts) {
        this.transactions = transactions;
        this.assembler = assembler;
        this.categories = categories;
        this.bankAccounts = bankAccounts;
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> list(UUID accountId, TransactionFilter filter, int page, int size) {
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

        Page<Transaction> result = transactions.findAll(spec, PageRequests.of(page, size, ORDER));
        List<TransactionResponse> content = assembler.toResponses(accountId, result.getContent());
        return new PageResponse<>(
                content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
