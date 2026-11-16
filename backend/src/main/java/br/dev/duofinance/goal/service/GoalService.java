package br.dev.duofinance.goal.service;

import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.goal.dto.CreateGoalRequest;
import br.dev.duofinance.goal.dto.GoalResponse;
import br.dev.duofinance.goal.dto.GoalsOverview;
import br.dev.duofinance.goal.dto.UpdateGoalRequest;
import br.dev.duofinance.goal.model.Goal;
import br.dev.duofinance.goal.repository.GoalRepository;
import br.dev.duofinance.goal.repository.GoalSavedQuery;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final GoalSettingsService settings;
    private final AccountRepository accounts;
    private final Clock clock;

    public GoalService(
            GoalRepository goals,
            GoalSavedQuery saved,
            GoalSettingsService settings,
            AccountRepository accounts,
            Clock clock) {
        this.goals = goals;
        this.saved = saved;
        this.settings = settings;
        this.accounts = accounts;
        this.clock = clock;
    }

    public YearMonth currentMonth() {
        return YearMonth.now(clock);
    }

    @Transactional(readOnly = true)
    public GoalsOverview overview(UUID accountId, YearMonth month) {
        Map<UUID, Long> savedByGoal = saved.savedByGoal(accountId);
        List<Goal> all = goals.findByAccountIdOrderByCreatedAtAsc(accountId);
        List<GoalResponse> active = all.stream()
                .filter(g -> !g.isCompleted())
                .map(g -> respond(g, savedByGoal))
                .toList();
        List<GoalResponse> completed = all.stream()
                .filter(Goal::isCompleted)
                .sorted(Comparator.comparing(Goal::getCompletedAt).reversed())
                .map(g -> respond(g, savedByGoal))
                .toList();
        long totalSaved = active.stream().mapToLong(GoalResponse::savedCents).sum();
        return new GoalsOverview(
                month, settings.get(accountId).salaryCategory(), 0L, totalSaved, active, completed, List.of());
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
        boolean taken = goals.findByAccountIdOrderByCreatedAtAsc(accountId).stream()
                .anyMatch(g -> !g.isCompleted() && !g.getId().equals(exceptId) && g.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new ApiException(ErrorCode.GOAL_NAME_TAKEN);
        }
    }

    private void requirePercentFits(UUID accountId, @Nullable UUID exceptId, int percent) {
        int others = goals.findByAccountIdOrderByCreatedAtAsc(accountId).stream()
                .filter(g -> !g.isCompleted() && !g.getId().equals(exceptId))
                .mapToInt(Goal::getMonthlyPercent)
                .sum();
        if (others + percent > 100) {
            throw new ApiException(ErrorCode.GOAL_PERCENT_CAP);
        }
    }

    private GoalResponse single(Goal goal) {
        return respond(goal, saved.savedByGoal(goal.getAccountId()));
    }

    private static GoalResponse respond(Goal goal, Map<UUID, Long> savedByGoal) {
        return GoalResponse.of(goal, savedByGoal.getOrDefault(goal.getId(), 0L), null);
    }
}
