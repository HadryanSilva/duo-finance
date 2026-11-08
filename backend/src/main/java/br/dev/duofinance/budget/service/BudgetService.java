package br.dev.duofinance.budget.service;

import br.dev.duofinance.budget.dto.BudgetResponse;
import br.dev.duofinance.budget.dto.CreateBudgetRequest;
import br.dev.duofinance.budget.dto.UpdateBudgetRequest;
import br.dev.duofinance.budget.model.Budget;
import br.dev.duofinance.budget.repository.BudgetProgressQuery;
import br.dev.duofinance.budget.repository.BudgetRepository;
import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.category.repository.CategoryRepository;
import br.dev.duofinance.category.service.CategoryService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.transaction.dto.TransactionResponse.CategoryRef;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes flush before returning so the unique constraint on category_id surfaces inside the transaction; the
 * controller maps it to BUDGET_EXISTS after the rollback.
 */
@Service
public class BudgetService {

    private final BudgetRepository budgets;
    private final CategoryRepository categories;
    private final CategoryService categoryService;
    private final BudgetProgressQuery progress;
    private final Clock clock;

    public BudgetService(
            BudgetRepository budgets,
            CategoryRepository categories,
            CategoryService categoryService,
            BudgetProgressQuery progress,
            Clock clock) {
        this.budgets = budgets;
        this.categories = categories;
        this.categoryService = categoryService;
        this.progress = progress;
        this.clock = clock;
    }

    public YearMonth currentMonth() {
        return YearMonth.now(clock);
    }

    /** Budgets ordered by category name (case-insensitive): one query each for budgets, categories and spending. */
    @Transactional(readOnly = true)
    public List<BudgetResponse> list(UUID accountId, YearMonth month) {
        return list(accountId, month, null);
    }

    /** As {@link #list(UUID, YearMonth)} for a caller that already has the month's spending; null loads it. */
    @Transactional(readOnly = true)
    public List<BudgetResponse> list(UUID accountId, YearMonth month, Map<UUID, Long> knownSpent) {
        List<Budget> all = budgets.findByAccountId(accountId);
        if (all.isEmpty()) {
            return List.of();
        }
        Set<UUID> categoryIds = all.stream().map(Budget::getCategoryId).collect(Collectors.toSet());
        Map<UUID, Category> categoryById = categories.findByAccountIdAndIdIn(accountId, categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        Map<UUID, Long> spent = knownSpent != null ? knownSpent : progress.spentByCategory(accountId, month);
        return all.stream()
                .map(b -> respond(b, categoryById.get(b.getCategoryId()), month, spent))
                .sorted(Comparator.comparing((BudgetResponse r) -> r.category().name(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(BudgetResponse::id))
                .toList();
    }

    @Transactional(readOnly = true)
    public BudgetResponse get(UUID accountId, UUID id, YearMonth month) {
        return single(requireOwned(accountId, id), month);
    }

    @Transactional
    public BudgetResponse create(UUID accountId, CreateBudgetRequest request) {
        try {
            categoryService.requireUsable(accountId, request.categoryId(), CategoryKind.EXPENSE);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.CATEGORY_KIND_MISMATCH) {
                throw new ApiException(ErrorCode.BUDGET_CATEGORY_INVALID);
            }
            throw e;
        }
        Budget budget = budgets.saveAndFlush(
                new Budget(accountId, request.categoryId(), request.monthlyLimitCents(), Instant.now(clock)));
        return single(budget, currentMonth());
    }

    @Transactional
    public BudgetResponse update(UUID accountId, UUID id, UpdateBudgetRequest request) {
        Budget budget = requireOwned(accountId, id);
        budget.setMonthlyLimitCents(request.monthlyLimitCents());
        budget.setUpdatedAt(Instant.now(clock));
        return single(budgets.saveAndFlush(budget), currentMonth());
    }

    @Transactional
    public void delete(UUID accountId, UUID id) {
        budgets.delete(requireOwned(accountId, id));
        budgets.flush();
    }

    private Budget requireOwned(UUID accountId, UUID id) {
        return budgets.findByIdAndAccountId(id, accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private BudgetResponse single(Budget budget, YearMonth month) {
        Category category = categories
                .findByIdAndAccountId(budget.getCategoryId(), budget.getAccountId())
                .orElseThrow();
        return respond(budget, category, month, progress.spentByCategory(budget.getAccountId(), month));
    }

    private static BudgetResponse respond(Budget budget, Category category, YearMonth month, Map<UUID, Long> spent) {
        CategoryRef ref = new CategoryRef(
                category.getId(),
                category.getName(),
                category.getColor(),
                category.getIcon(),
                category.getArchivedAt() != null);
        return BudgetResponse.of(budget, ref, month, spent.getOrDefault(budget.getCategoryId(), 0L));
    }
}
