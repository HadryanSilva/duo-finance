package br.dev.duofinance.dashboard.service;

import br.dev.duofinance.bankaccount.repository.BankAccountBalanceQuery;
import br.dev.duofinance.bankaccount.repository.BankAccountRepository;
import br.dev.duofinance.budget.repository.BudgetProgressQuery;
import br.dev.duofinance.budget.service.BudgetService;
import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.repository.CategoryRepository;
import br.dev.duofinance.dashboard.dto.DashboardResponse;
import br.dev.duofinance.dashboard.dto.DashboardResponse.AccountBalance;
import br.dev.duofinance.dashboard.dto.DashboardResponse.CategorySpend;
import br.dev.duofinance.dashboard.dto.DashboardResponse.UpcomingOccurrence;
import br.dev.duofinance.dashboard.repository.MonthlyTotalsQuery;
import br.dev.duofinance.dashboard.repository.MonthlyTotalsQuery.Totals;
import br.dev.duofinance.recurring.model.RecurringRule;
import br.dev.duofinance.recurring.repository.RecurringRuleRepository;
import br.dev.duofinance.recurring.service.RecurrenceSchedule;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whole dashboard in a fixed number of statements (totals with currency, bank accounts, balances, spending, budgets,
 * their categories, spending categories, active rules), whatever the data volume.
 */
@Service
public class DashboardService {

    static final int UPCOMING_DAYS = 30;
    static final int UPCOMING_LIMIT = 20;

    private final MonthlyTotalsQuery totalsQuery;
    private final BankAccountRepository bankAccounts;
    private final BankAccountBalanceQuery balanceQuery;
    private final BudgetProgressQuery progress;
    private final BudgetService budgetService;
    private final CategoryRepository categories;
    private final RecurringRuleRepository rules;
    private final Clock clock;

    public DashboardService(
            MonthlyTotalsQuery totalsQuery,
            BankAccountRepository bankAccounts,
            BankAccountBalanceQuery balanceQuery,
            BudgetProgressQuery progress,
            BudgetService budgetService,
            CategoryRepository categories,
            RecurringRuleRepository rules,
            Clock clock) {
        this.totalsQuery = totalsQuery;
        this.bankAccounts = bankAccounts;
        this.balanceQuery = balanceQuery;
        this.progress = progress;
        this.budgetService = budgetService;
        this.categories = categories;
        this.rules = rules;
        this.clock = clock;
    }

    public YearMonth currentMonth() {
        return YearMonth.now(clock);
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(UUID accountId, YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        LocalDate balancesAsOf = today.isAfter(month.atEndOfMonth()) ? month.atEndOfMonth() : today;

        Totals totals = totalsQuery.totals(accountId, month);

        Map<UUID, Long> balances = balanceQuery.balances(accountId, balancesAsOf);
        List<AccountBalance> accounts = bankAccounts.list(accountId, false).stream()
                .map(b -> new AccountBalance(
                        b.getId(),
                        b.getName(),
                        b.getType(),
                        balances.getOrDefault(b.getId(), b.getInitialBalanceCents())))
                .toList();
        long totalBalance =
                accounts.stream().mapToLong(AccountBalance::balanceCents).sum();

        Map<UUID, Long> spent = progress.spentByCategory(accountId, month);

        return new DashboardResponse(
                month,
                totals.currency(),
                balancesAsOf,
                totalBalance,
                accounts,
                totals.incomeCents(),
                totals.expenseCents(),
                spending(accountId, spent),
                budgetService.list(accountId, month, spent),
                upcoming(accountId, today));
    }

    /** Archived categories are included: the spending happened. */
    private List<CategorySpend> spending(UUID accountId, Map<UUID, Long> spent) {
        if (spent.isEmpty()) {
            return List.of();
        }
        Map<UUID, Category> byId = categories.findByAccountIdAndIdIn(accountId, spent.keySet()).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        return spent.entrySet().stream()
                .map(e -> {
                    Category c = byId.get(e.getKey());
                    return new CategorySpend(c.getId(), c.getName(), c.getColor(), c.getIcon(), e.getValue());
                })
                .sorted(Comparator.comparingLong(CategorySpend::totalCents)
                        .reversed()
                        .thenComparing(CategorySpend::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(CategorySpend::categoryId))
                .toList();
    }

    private List<UpcomingOccurrence> upcoming(UUID accountId, LocalDate today) {
        LocalDate until = today.plusDays(UPCOMING_DAYS);
        return rules.findActive(accountId).stream()
                .flatMap(rule -> {
                    LocalDate from = rule.getNextRunDate().isAfter(today) ? rule.getNextRunDate() : today;
                    return RecurrenceSchedule.between(
                                    rule.getStartDate(), rule.getFrequency(), rule.getEndDate(), from, until)
                            .stream()
                            .map(date -> occurrence(rule, date));
                })
                .sorted(Comparator.comparing(UpcomingOccurrence::date).thenComparing(UpcomingOccurrence::ruleId))
                .limit(UPCOMING_LIMIT)
                .toList();
    }

    private static UpcomingOccurrence occurrence(RecurringRule rule, LocalDate date) {
        return new UpcomingOccurrence(
                rule.getId(), date, rule.getKind(), rule.getAmountCents(), rule.getDescription(), rule.getCategoryId());
    }
}
