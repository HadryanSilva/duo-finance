package br.dev.duofinance.category.service;

import br.dev.duofinance.category.dto.CategoryRequest;
import br.dev.duofinance.category.dto.CategoryResponse;
import br.dev.duofinance.category.dto.UpdateCategoryRequest;
import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.category.repository.CategoryRepository;
import br.dev.duofinance.recurring.repository.RecurringRuleRepository;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Methods that write flush before returning so unique/foreign-key violations surface inside the transaction; the
 * controller maps them to error codes after the rollback.
 */
@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final RecurringRuleRepository rules;
    private final Clock clock;

    public CategoryService(CategoryRepository categories, RecurringRuleRepository rules, Clock clock) {
        this.categories = categories;
        this.rules = rules;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(UUID accountId, CategoryKind kind, boolean includeArchived) {
        return categories.list(accountId, kind, includeArchived).stream()
                .map(CategoryResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(UUID accountId, UUID id) {
        return CategoryResponse.of(requireOwned(accountId, id));
    }

    @Transactional
    public CategoryResponse create(UUID accountId, CategoryRequest request) {
        Category category = new Category(
                accountId, request.name(), request.kind(), request.color(), request.icon(), false, Instant.now(clock));
        return CategoryResponse.of(categories.saveAndFlush(category));
    }

    @Transactional
    public CategoryResponse update(UUID accountId, UUID id, UpdateCategoryRequest request) {
        Category category = requireOwned(accountId, id);
        category.setName(request.name());
        category.setColor(request.color());
        category.setIcon(request.icon());
        category.setUpdatedAt(Instant.now(clock));
        return CategoryResponse.of(categories.saveAndFlush(category));
    }

    /** An active, not finished recurring rule using the category blocks archiving (IN_USE_BY_ACTIVE_RULE). */
    @Transactional
    public CategoryResponse archive(UUID accountId, UUID id) {
        Category category = requireOwned(accountId, id);
        if (category.getArchivedAt() == null) {
            if (rules.activeRuleUsesCategory(id)) {
                throw new ApiException(ErrorCode.IN_USE_BY_ACTIVE_RULE);
            }
            Instant now = Instant.now(clock);
            category.setArchivedAt(now);
            category.setUpdatedAt(now);
        }
        return CategoryResponse.of(categories.saveAndFlush(category));
    }

    @Transactional
    public CategoryResponse unarchive(UUID accountId, UUID id) {
        Category category = requireOwned(accountId, id);
        if (category.getArchivedAt() != null) {
            category.setArchivedAt(null);
            category.setUpdatedAt(Instant.now(clock));
        }
        return CategoryResponse.of(categories.saveAndFlush(category));
    }

    @Transactional
    public void delete(UUID accountId, UUID id) {
        categories.delete(requireOwned(accountId, id));
        categories.flush();
    }

    /** Category of the account, or 404. */
    @Transactional(readOnly = true)
    public Category requireOwned(UUID accountId, UUID id) {
        return categories.findByIdAndAccountId(id, accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /**
     * Category that can receive new transactions: 404 if not owned, then CATEGORY_KIND_MISMATCH, then
     * CATEGORY_ARCHIVED.
     */
    @Transactional(readOnly = true)
    public Category requireUsable(UUID accountId, UUID categoryId, CategoryKind expectedKind) {
        Category category = requireOwned(accountId, categoryId);
        if (category.getKind() != expectedKind) {
            throw new ApiException(ErrorCode.CATEGORY_KIND_MISMATCH);
        }
        if (category.getArchivedAt() != null) {
            throw new ApiException(ErrorCode.CATEGORY_ARCHIVED);
        }
        return category;
    }
}
