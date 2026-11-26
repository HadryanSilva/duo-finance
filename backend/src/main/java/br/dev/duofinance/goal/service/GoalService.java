package br.dev.duofinance.goal.service;

import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.goal.dto.CreateGoalRequest;
import br.dev.duofinance.goal.dto.GoalMonthTarget;
import br.dev.duofinance.goal.dto.GoalResponse;
import br.dev.duofinance.goal.dto.GoalStatus;
import br.dev.duofinance.goal.dto.GoalsOverview;
import br.dev.duofinance.goal.dto.UpdateGoalRequest;
import br.dev.duofinance.goal.model.Goal;
import br.dev.duofinance.goal.repository.GoalRepository;
import br.dev.duofinance.goal.repository.GoalSalaryQuery;
import br.dev.duofinance.goal.repository.GoalSavedQuery;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.time.RunningMonths;
import br.dev.duofinance.transaction.dto.TransactionResponse.CategoryRef;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write takes the account row lock first, then flushes so the active-name unique index surfaces inside the
 * transaction; the controller maps a race on it to GOAL_NAME_TAKEN after the rollback.
 */
@Service
public class GoalService {

    private final GoalRepository goals;
    private final GoalSavedQuery saved;
    private final GoalSalaryQuery salaries;
    private final GoalSettingsService settings;
    private final AccountRepository accounts;
    private final Clock clock;

    public GoalService(
            GoalRepository goals,
            GoalSavedQuery saved,
            GoalSalaryQuery salaries,
            GoalSettingsService settings,
            AccountRepository accounts,
            Clock clock) {
        this.goals = goals;
        this.saved = saved;
        this.salaries = salaries;
        this.settings = settings;
        this.accounts = accounts;
        this.clock = clock;
    }

    public YearMonth currentMonth() {
        return YearMonth.now(clock);
    }

    @Transactional(readOnly = true)
    public GoalsOverview overview(UUID accountId, YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        YearMonth current = YearMonth.from(today);
        // Read once: an archived salary category is still shown (with a warning) but targets nothing.
        CategoryRef salaryCategory = settings.get(accountId).salaryCategory();
        long salary = salaryOf(accountId, salaryCategory, month);
        long currentSalary = month.equals(current) ? salary : salaryOf(accountId, salaryCategory, current);
        Map<UUID, Long> savedByGoal = saved.savedByGoal(accountId);
        Map<UUID, List<Long>> lastMonthlyTotals = saved.lastMonthlyTotals(accountId);
        Map<UUID, Long> contributedThisMonth = saved.contributedIn(accountId, current);
        Set<UUID> skippedThisMonth = saved.skippedIn(accountId, current);
        List<Goal> all = goals.findByAccountIdOrderByCreatedAtAscIdAsc(accountId);
        List<GoalResponse> active = all.stream()
                .filter(g -> !g.isCompleted())
                .map(g -> {
                    long savedCents = savedByGoal.getOrDefault(g.getId(), 0L);
                    long contributed = contributedThisMonth.getOrDefault(g.getId(), 0L);
                    long target = targetCents(
                            currentSalary, g.getMonthlyPercent(), g.getTargetCents() - savedCents, contributed);
                    boolean decided = skippedThisMonth.contains(g.getId())
                            || (target > 0 ? contributed >= target : contributed > 0);
                    return GoalResponse.of(
                            g,
                            savedCents,
                            GoalProjections.of(
                                    g.getTargetCents(),
                                    savedCents,
                                    g.getTargetDate(),
                                    lastMonthlyTotals.getOrDefault(g.getId(), List.of()),
                                    today,
                                    decided));
                })
                .toList();
        List<GoalResponse> completed = all.stream()
                .filter(Goal::isCompleted)
                .sorted(Comparator.comparing(Goal::getCompletedAt).reversed())
                .map(g -> respond(g, savedByGoal))
                .toList();
        long totalSaved = active.stream().mapToLong(GoalResponse::savedCents).sum();
        return new GoalsOverview(
                month,
                salaryCategory,
                salary,
                totalSaved,
                active,
                completed,
                monthTargets(
                        accountId,
                        month,
                        salary,
                        all,
                        active,
                        month.equals(current) ? contributedThisMonth : saved.contributedIn(accountId, month),
                        month.equals(current) ? skippedThisMonth : saved.skippedIn(accountId, month)));
    }

    private long salaryOf(UUID accountId, @Nullable CategoryRef salaryCategory, YearMonth month) {
        return salaryCategory == null || salaryCategory.archived()
                ? 0L
                : salaries.salary(accountId, salaryCategory.id(), month);
    }

    /**
     * Every active goal with a monthly percentage that already existed in the month, with what was put towards it.
     * Past months are listed too; a future one, or one yet to begin in the couple's time zone, is not (see
     * {@link RunningMonths}).
     */
    private List<GoalMonthTarget> monthTargets(
            UUID accountId,
            YearMonth month,
            long salary,
            List<Goal> all,
            List<GoalResponse> active,
            Map<UUID, Long> contributed,
            Set<UUID> skipped) {
        if (RunningMonths.isFuture(month, clock)) {
            return List.of();
        }
        List<GoalResponse> candidates = active.stream()
                .filter(g -> g.status() == GoalStatus.ACTIVE && g.monthlyPercent() > 0)
                .toList();
        Set<UUID> ids = candidates.stream().map(GoalResponse::id).collect(Collectors.toSet());
        Map<UUID, YearMonth> firstMonths = saved.firstMonthsByGoal(
                accountId, all.stream().filter(g -> ids.contains(g.getId())).toList());
        return candidates.stream()
                .filter(g -> !firstMonths.get(g.id()).isAfter(month))
                .map(g -> {
                    long done = contributed.getOrDefault(g.id(), 0L);
                    return new GoalMonthTarget(
                            g.id(),
                            g.name(),
                            month,
                            g.monthlyPercent(),
                            targetCents(salary, g.monthlyPercent(), g.remainingCents(), done),
                            done,
                            skipped.contains(g.id()));
                })
                .toList();
    }

    /** The share of the salary, capped at what the goal still needed before this month's contributions. */
    private static long targetCents(long salary, int percent, long remainingCents, long contributedCents) {
        if (salary <= 0 || percent <= 0) {
            return 0;
        }
        return Math.max(0, suggestedCents(salary, percent, remainingCents + contributedCents));
    }

    @Transactional
    public GoalResponse create(UUID accountId, CreateGoalRequest request) {
        lockAccount(accountId);
        String name = request.name().strip();
        requireNameFree(accountId, name, null);
        requirePercentFits(accountId, null, request.monthlyPercent());
        Goal goal = goals.saveAndFlush(new Goal(
                accountId,
                name,
                request.targetCents(),
                request.targetDate(),
                request.monthlyPercent(),
                Instant.now(clock)));
        return single(goal);
    }

    @Transactional
    public GoalResponse update(UUID accountId, UUID id, UpdateGoalRequest request) {
        lockAccount(accountId);
        Goal goal = requireOwned(accountId, id);
        if (goal.isCompleted()) {
            throw new ApiException(ErrorCode.GOAL_COMPLETED);
        }
        String name = request.name().strip();
        requireNameFree(accountId, name, id);
        requirePercentFits(accountId, id, request.monthlyPercent());
        goal.setName(name);
        goal.setTargetCents(request.targetCents());
        goal.setTargetDate(request.targetDate());
        goal.setMonthlyPercent(request.monthlyPercent());
        goal.setUpdatedAt(Instant.now(clock));
        return single(goals.saveAndFlush(goal));
    }

    @Transactional
    public void delete(UUID accountId, UUID id) {
        lockAccount(accountId);
        goals.delete(requireOwned(accountId, id));
        goals.flush();
    }

    @Transactional
    public GoalResponse complete(UUID accountId, UUID id) {
        lockAccount(accountId);
        Goal goal = requireOwned(accountId, id);
        if (goal.isCompleted()) {
            throw new ApiException(ErrorCode.GOAL_COMPLETED);
        }
        Instant now = Instant.now(clock);
        goal.setCompletedAt(now);
        goal.setUpdatedAt(now);
        return single(goals.saveAndFlush(goal));
    }

    @Transactional
    public GoalResponse reopen(UUID accountId, UUID id) {
        lockAccount(accountId);
        Goal goal = requireOwned(accountId, id);
        if (!goal.isCompleted()) {
            throw new ApiException(ErrorCode.GOAL_COMPLETED);
        }
        requireNameFree(accountId, goal.getName(), id);
        requirePercentFits(accountId, id, goal.getMonthlyPercent());
        goal.setCompletedAt(null);
        goal.setUpdatedAt(Instant.now(clock));
        return single(goals.saveAndFlush(goal));
    }

    /** min(round-half-up(salary × percent / 100), remaining). */
    static long suggestedCents(long salaryCents, int percent, long remainingCents) {
        return Math.min((salaryCents * percent + 50) / 100, remainingCents);
    }

    private void lockAccount(UUID accountId) {
        // Lock order for every goal write: the account row first.
        accounts.findByIdForUpdate(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Goal requireOwned(UUID accountId, UUID id) {
        return goals.findByIdAndAccountId(id, accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private void requireNameFree(UUID accountId, String name, @Nullable UUID exceptId) {
        boolean taken = goals.findByAccountIdOrderByCreatedAtAscIdAsc(accountId).stream()
                .anyMatch(g -> !g.isCompleted()
                        && !g.getId().equals(exceptId)
                        && g.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new ApiException(ErrorCode.GOAL_NAME_TAKEN);
        }
    }

    private void requirePercentFits(UUID accountId, @Nullable UUID exceptId, int percent) {
        int others = goals.findByAccountIdOrderByCreatedAtAscIdAsc(accountId).stream()
                .filter(g -> !g.isCompleted() && !g.getId().equals(exceptId))
                .mapToInt(Goal::getMonthlyPercent)
                .sum();
        if (others + percent > 100) {
            throw new ApiException(ErrorCode.GOAL_PERCENT_CAP);
        }
    }

    private GoalResponse single(Goal goal) {
        return GoalResponse.of(goal, saved.savedOf(goal.getId()), null);
    }

    private static GoalResponse respond(Goal goal, Map<UUID, Long> savedByGoal) {
        return GoalResponse.of(goal, savedByGoal.getOrDefault(goal.getId(), 0L), null);
    }
}
