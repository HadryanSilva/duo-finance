package br.dev.duofinance.goal.service;

import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.goal.dto.ContributeRequest;
import br.dev.duofinance.goal.dto.GoalAmountRequest;
import br.dev.duofinance.goal.dto.GoalMovementResponse;
import br.dev.duofinance.goal.dto.GoalResponse;
import br.dev.duofinance.goal.dto.SkipRequest;
import br.dev.duofinance.goal.model.Goal;
import br.dev.duofinance.goal.model.GoalMovement;
import br.dev.duofinance.goal.model.GoalMovementType;
import br.dev.duofinance.goal.repository.GoalMovementRepository;
import br.dev.duofinance.goal.repository.GoalRepository;
import br.dev.duofinance.goal.repository.GoalSalaryQuery;
import br.dev.duofinance.goal.repository.GoalSavedQuery;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.transaction.dto.TransactionResponse.AuthorRef;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write locks the account row first and then loads the goal, so concurrent movements on the same account
 * serialise. The partial unique index on (goal, month) stays as the last line of defence; the controller maps it to
 * GOAL_MONTH_TAKEN after the rollback.
 */
@Service
public class GoalMovementService {

    private final GoalRepository goals;
    private final GoalMovementRepository movements;
    private final GoalSavedQuery saved;
    private final GoalSalaryQuery salaries;
    private final GoalSettingsService salarySettings;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final Clock clock;

    public GoalMovementService(
            GoalRepository goals,
            GoalMovementRepository movements,
            GoalSavedQuery saved,
            GoalSalaryQuery salaries,
            GoalSettingsService salarySettings,
            AccountRepository accounts,
            UserRepository users,
            Clock clock) {
        this.goals = goals;
        this.movements = movements;
        this.saved = saved;
        this.salaries = salaries;
        this.salarySettings = salarySettings;
        this.accounts = accounts;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<GoalMovementResponse> list(UUID accountId, UUID goalId) {
        requireOwned(accountId, goalId);
        List<GoalMovement> rows = movements.findByGoalIdOrderByCreatedAtDesc(goalId);
        Set<UUID> authorIds = rows.stream()
                .map(GoalMovement::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, User> userById = authorIds.isEmpty()
                ? Map.of()
                : users.findAllById(authorIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return rows.stream()
                .map(m -> new GoalMovementResponse(
                        m.getId(),
                        m.getType(),
                        m.getAmountCents(),
                        m.getMonth() == null ? null : YearMonth.from(m.getMonth()),
                        m.getNote(),
                        m.getCreatedBy() == null ? null : authorRef(userById.get(m.getCreatedBy())),
                        m.getCreatedAt()))
                .toList();
    }

    @Transactional
    public GoalResponse contribute(UUID accountId, UUID userId, UUID goalId, ContributeRequest request) {
        Goal goal = lockAndLoadActive(accountId, goalId);
        YearMonth month = requireFreeMonth(goal, request.month());
        long salary = salarySettings
                .usableSalaryCategoryId(accountId)
                .map(id -> salaries.salary(accountId, id, month))
                .orElse(0L);
        if (salary <= 0) {
            throw new ApiException(ErrorCode.GOAL_NO_SALARY);
        }
        long remaining = remainingOf(goal, savedOf(goal));
        Long requested = request.amountCents();
        long amount =
                requested != null ? requested : GoalService.suggestedCents(salary, goal.getMonthlyPercent(), remaining);
        requireAmount(amount, remaining);
        return record(goal, userId, GoalMovementType.CONTRIBUTION, amount, month, null);
    }

    @Transactional
    public GoalResponse skip(UUID accountId, UUID userId, UUID goalId, SkipRequest request) {
        Goal goal = lockAndLoadActive(accountId, goalId);
        YearMonth month = requireFreeMonth(goal, request.month());
        return record(goal, userId, GoalMovementType.SKIP, 0L, month, null);
    }

    @Transactional
    public GoalResponse deposit(UUID accountId, UUID userId, UUID goalId, GoalAmountRequest request) {
        Goal goal = lockAndLoadActive(accountId, goalId);
        long amount = request.amountCents();
        requireAmount(amount, remainingOf(goal, savedOf(goal)));
        return record(goal, userId, GoalMovementType.DEPOSIT, amount, null, request.note());
    }

    @Transactional
    public GoalResponse withdraw(UUID accountId, UUID userId, UUID goalId, GoalAmountRequest request) {
        Goal goal = lockAndLoadActive(accountId, goalId);
        long amount = request.amountCents();
        requireAmount(amount, savedOf(goal));
        return record(goal, userId, GoalMovementType.WITHDRAWAL, amount, null, request.note());
    }

    @Transactional
    public GoalResponse undo(UUID accountId, UUID goalId, UUID movementId) {
        Goal goal = lockAndLoadActive(accountId, goalId);
        GoalMovement movement = movements
                .findByIdAndGoalId(movementId, goalId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        long effect = movement.getType() == GoalMovementType.WITHDRAWAL
                ? -movement.getAmountCents()
                : movement.getAmountCents();
        long after = savedOf(goal) - effect;
        if (after < 0) {
            throw new ApiException(ErrorCode.GOAL_AMOUNT_INVALID);
        }
        movements.delete(movement);
        movements.flush();
        return GoalResponse.of(goal, after, null);
    }

    private Goal lockAndLoadActive(UUID accountId, UUID goalId) {
        // Lock order for every goal write: the account row first, then the goal.
        accounts.findByIdForUpdate(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Goal goal = requireOwned(accountId, goalId);
        if (goal.isCompleted()) {
            throw new ApiException(ErrorCode.GOAL_COMPLETED);
        }
        return goal;
    }

    private Goal requireOwned(UUID accountId, UUID goalId) {
        return goals.findByIdAndAccountId(goalId, accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private YearMonth requireFreeMonth(Goal goal, YearMonth month) {
        YearMonth first = YearMonth.from(goal.getCreatedAt().atZone(clock.getZone()));
        if (month.isBefore(first) || month.isAfter(YearMonth.now(clock))) {
            throw new ApiException(ErrorCode.GOAL_MONTH_INVALID);
        }
        if (movements.existsByGoalIdAndMonth(goal.getId(), month.atDay(1))) {
            throw new ApiException(ErrorCode.GOAL_MONTH_TAKEN);
        }
        return month;
    }

    private long savedOf(Goal goal) {
        return saved.savedByGoal(goal.getAccountId()).getOrDefault(goal.getId(), 0L);
    }

    private static long remainingOf(Goal goal, long saved) {
        return Math.max(goal.getTargetCents() - saved, 0);
    }

    private static void requireAmount(long amount, long max) {
        if (amount < 1 || amount > max) {
            throw new ApiException(ErrorCode.GOAL_AMOUNT_INVALID);
        }
    }

    private GoalResponse record(
            Goal goal,
            UUID userId,
            GoalMovementType type,
            long amount,
            @Nullable YearMonth month,
            @Nullable String note) {
        movements.saveAndFlush(new GoalMovement(
                goal.getId(),
                goal.getAccountId(),
                type,
                amount,
                month == null ? null : month.atDay(1),
                note == null || note.isBlank() ? null : note.strip(),
                userId,
                Instant.now(clock)));
        return GoalResponse.of(goal, savedOf(goal), null);
    }

    private static @Nullable AuthorRef authorRef(@Nullable User u) {
        return u == null ? null : new AuthorRef(u.getId(), u.getDisplayName());
    }
}
