package br.dev.duofinance.goal.service;

import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.category.repository.CategoryRepository;
import br.dev.duofinance.category.service.CategoryService;
import br.dev.duofinance.goal.dto.GoalSettingsResponse;
import br.dev.duofinance.goal.dto.UpdateGoalSettingsRequest;
import br.dev.duofinance.goal.repository.GoalSettingsRepository;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.transaction.dto.TransactionResponse.CategoryRef;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoalSettingsService {

    private final GoalSettingsRepository settings;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final CategoryService categoryService;

    public GoalSettingsService(
            GoalSettingsRepository settings,
            AccountRepository accounts,
            CategoryRepository categories,
            CategoryService categoryService) {
        this.settings = settings;
        this.accounts = accounts;
        this.categories = categories;
        this.categoryService = categoryService;
    }

    @Transactional(readOnly = true)
    public GoalSettingsResponse get(UUID accountId) {
        return settings.salaryCategoryId(accountId)
                .flatMap(id -> categories.findByAccountIdAndIdIn(accountId, Set.of(id)).stream()
                        .findFirst())
                .map(c -> new GoalSettingsResponse(ref(c)))
                .orElseGet(() -> new GoalSettingsResponse(null));
    }

    /** The salary category id only when it is set, still exists in the account and is not archived. */
    @Transactional(readOnly = true)
    public Optional<UUID> usableSalaryCategoryId(UUID accountId) {
        return settings.salaryCategoryId(accountId)
                .filter(id -> categories.findByAccountIdAndIdIn(accountId, Set.of(id)).stream()
                        .anyMatch(c -> c.getArchivedAt() == null));
    }

    @Transactional
    public GoalSettingsResponse update(UUID accountId, UpdateGoalSettingsRequest request) {
        // Lock order for every goal write: the account row first.
        accounts.findByIdForUpdate(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        UUID categoryId = request.salaryCategoryId();
        if (categoryId == null) {
            settings.setSalaryCategoryId(accountId, null);
            return new GoalSettingsResponse(null);
        }
        Category category;
        try {
            category = categoryService.requireUsable(accountId, categoryId, CategoryKind.INCOME);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.CATEGORY_KIND_MISMATCH || e.code() == ErrorCode.CATEGORY_ARCHIVED) {
                throw new ApiException(ErrorCode.GOAL_SALARY_CATEGORY_INVALID);
            }
            throw e;
        }
        settings.setSalaryCategoryId(accountId, categoryId);
        return new GoalSettingsResponse(ref(category));
    }

    private static CategoryRef ref(Category category) {
        return new CategoryRef(
                category.getId(),
                category.getName(),
                category.getColor(),
                category.getIcon(),
                category.getArchivedAt() != null);
    }
}
