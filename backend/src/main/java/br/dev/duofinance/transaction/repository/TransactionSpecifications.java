package br.dev.duofinance.transaction.repository;

import br.dev.duofinance.transaction.model.Transaction;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class TransactionSpecifications {

    private static final char ESCAPE = '\\';

    private TransactionSpecifications() {}

    public static Specification<Transaction> inAccount(UUID accountId) {
        return (root, query, cb) -> cb.equal(root.get("accountId"), accountId);
    }

    public static Specification<Transaction> dateFrom(LocalDate from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.<LocalDate>get("date"), from);
    }

    public static Specification<Transaction> dateTo(LocalDate to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.<LocalDate>get("date"), to);
    }

    public static Specification<Transaction> ofKind(TransactionKind kind) {
        return (root, query, cb) -> cb.equal(root.get("kind"), kind);
    }

    public static Specification<Transaction> inCategory(UUID categoryId) {
        return (root, query, cb) -> cb.equal(root.get("categoryId"), categoryId);
    }

    /** Matches the origin or the destination bank account. */
    public static Specification<Transaction> involvingBankAccount(UUID bankAccountId) {
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("bankAccountId"), bankAccountId),
                cb.equal(root.get("destinationBankAccountId"), bankAccountId));
    }

    public static Specification<Transaction> createdBy(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("createdBy"), userId);
    }

    /** Literal, case-insensitive substring match; a null description never matches. */
    public static Specification<Transaction> descriptionContains(String text) {
        String pattern = "%" + escapeLike(text) + "%";
        return (root, query, cb) ->
                cb.like(cb.lower(root.<String>get("description")), cb.lower(cb.literal(pattern)), ESCAPE);
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
