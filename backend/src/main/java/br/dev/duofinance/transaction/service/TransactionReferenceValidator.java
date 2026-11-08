package br.dev.duofinance.transaction.service;

import br.dev.duofinance.bankaccount.model.BankAccount;
import br.dev.duofinance.bankaccount.service.BankAccountService;
import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.category.service.CategoryService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Validates the shape and references of a transaction (or recurring rule) in a fixed order: shape, then ownership
 * (404), then kind and archival (409). Validating in Java first gives stable codes instead of a database CHECK failure.
 */
@Component
public class TransactionReferenceValidator {

    /** References the record being edited already holds; an archived reference equal to the same slot is accepted. */
    public record ReferenceSnapshot(UUID categoryId, UUID bankAccountId, UUID destinationBankAccountId) {

        public static ReferenceSnapshot none() {
            return new ReferenceSnapshot(null, null, null);
        }
    }

    private final CategoryService categories;
    private final BankAccountService bankAccounts;

    public TransactionReferenceValidator(CategoryService categories, BankAccountService bankAccounts) {
        this.categories = categories;
        this.bankAccounts = bankAccounts;
    }

    public void validate(
            UUID accountId,
            TransactionKind kind,
            UUID categoryId,
            UUID bankAccountId,
            UUID destinationBankAccountId,
            ReferenceSnapshot current) {
        validateShape(kind, categoryId, bankAccountId, destinationBankAccountId);

        Category category = categoryId == null ? null : categories.requireOwned(accountId, categoryId);
        BankAccount origin = bankAccountId == null ? null : bankAccounts.requireOwned(accountId, bankAccountId);
        BankAccount destination = destinationBankAccountId == null
                ? null
                : bankAccounts.requireOwned(accountId, destinationBankAccountId);

        if (category != null) {
            CategoryKind expected = kind == TransactionKind.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
            if (category.getKind() != expected) {
                throw new ApiException(ErrorCode.CATEGORY_KIND_MISMATCH);
            }
            if (category.getArchivedAt() != null && !Objects.equals(categoryId, current.categoryId())) {
                throw new ApiException(ErrorCode.CATEGORY_ARCHIVED);
            }
        }
        requireNotArchivedUnlessKept(origin, current.bankAccountId());
        requireNotArchivedUnlessKept(destination, current.destinationBankAccountId());
    }

    private static void validateShape(
            TransactionKind kind, UUID categoryId, UUID bankAccountId, UUID destinationBankAccountId) {
        if (kind == TransactionKind.TRANSFER) {
            if (categoryId != null
                    || bankAccountId == null
                    || destinationBankAccountId == null
                    || bankAccountId.equals(destinationBankAccountId)) {
                throw new ApiException(ErrorCode.TRANSFER_INVALID);
            }
            return;
        }
        if (categoryId == null) {
            throw ApiException.invalidField("categoryId", "NotNull");
        }
        if (destinationBankAccountId != null) {
            throw ApiException.invalidField("destinationBankAccountId", "NotAllowed");
        }
    }

    private static void requireNotArchivedUnlessKept(BankAccount account, UUID currentId) {
        if (account != null && account.isArchived() && !account.getId().equals(currentId)) {
            throw new ApiException(ErrorCode.BANK_ACCOUNT_ARCHIVED);
        }
    }
}
