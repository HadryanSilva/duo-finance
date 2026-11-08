package br.dev.duofinance.transaction.dto;

import br.dev.duofinance.bankaccount.model.BankAccount;
import br.dev.duofinance.bankaccount.repository.BankAccountRepository;
import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.repository.CategoryRepository;
import br.dev.duofinance.transaction.dto.TransactionResponse.AuthorRef;
import br.dev.duofinance.transaction.dto.TransactionResponse.BankAccountRef;
import br.dev.duofinance.transaction.dto.TransactionResponse.CategoryRef;
import br.dev.duofinance.transaction.model.Transaction;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds responses with one query per referenced type, never one per transaction. */
@Component
public class TransactionResponseAssembler {

    private final CategoryRepository categories;
    private final BankAccountRepository bankAccounts;
    private final UserRepository users;

    public TransactionResponseAssembler(
            CategoryRepository categories, BankAccountRepository bankAccounts, UserRepository users) {
        this.categories = categories;
        this.bankAccounts = bankAccounts;
        this.users = users;
    }

    public List<TransactionResponse> toResponses(UUID accountId, List<Transaction> transactions) {
        if (transactions.isEmpty()) {
            return List.of();
        }
        Set<UUID> categoryIds = ids(transactions, Transaction::getCategoryId);
        Set<UUID> bankIds = ids(transactions, Transaction::getBankAccountId);
        bankIds.addAll(ids(transactions, Transaction::getDestinationBankAccountId));
        Set<UUID> authorIds = ids(transactions, Transaction::getCreatedBy);

        Map<UUID, Category> categoryById = categoryIds.isEmpty()
                ? Map.of()
                : categories.findByAccountIdAndIdIn(accountId, categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Function.identity()));
        Map<UUID, BankAccount> bankById = bankIds.isEmpty()
                ? Map.of()
                : bankAccounts.findByAccountIdAndIdIn(accountId, bankIds).stream()
                        .collect(Collectors.toMap(BankAccount::getId, Function.identity()));
        Map<UUID, User> userById = authorIds.isEmpty()
                ? Map.of()
                : users.findAllById(authorIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));

        return transactions.stream()
                .map(t -> new TransactionResponse(
                        t.getId(),
                        t.getKind(),
                        t.getAmountCents(),
                        t.getDate(),
                        t.getDescription(),
                        categoryRef(lookup(categoryById, t.getCategoryId())),
                        bankRef(lookup(bankById, t.getBankAccountId())),
                        bankRef(lookup(bankById, t.getDestinationBankAccountId())),
                        authorRef(lookup(userById, t.getCreatedBy())),
                        t.getRecurringRuleId(),
                        t.getCreatedAt(),
                        t.getUpdatedAt()))
                .toList();
    }

    private static <T> T lookup(Map<UUID, T> map, UUID id) {
        return id == null ? null : map.get(id);
    }

    private static Set<UUID> ids(List<Transaction> transactions, Function<Transaction, UUID> getter) {
        return transactions.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static CategoryRef categoryRef(Category c) {
        return c == null
                ? null
                : new CategoryRef(c.getId(), c.getName(), c.getColor(), c.getIcon(), c.getArchivedAt() != null);
    }

    private static BankAccountRef bankRef(BankAccount b) {
        return b == null ? null : new BankAccountRef(b.getId(), b.getName(), b.isArchived());
    }

    private static AuthorRef authorRef(User u) {
        return u == null ? null : new AuthorRef(u.getId(), u.getDisplayName());
    }
}
