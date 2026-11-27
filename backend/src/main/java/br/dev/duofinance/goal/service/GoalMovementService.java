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
import br.dev.duofinance.goal.repository.GoalSavedQuery;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.time.RunningMonths;
import br.dev.duofinance.transaction.dto.TransactionResponse.AuthorRef;
import br.dev.duofinance.transaction.model.TransactionKind;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * serialise. The partial unique index on (goal, month) for SKIP rows stays as the last line of defence; the
 * controller maps it to GOAL_MONTH_TAKEN after the rollback.
 */
@Service
public class GoalMovementService {

    private final GoalRepository goals;
    private final GoalMovementRepository movements;
    private final GoalSavedQuery saved;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final Clock clock;

    public GoalMovementService(
            GoalRepository goals,
            GoalMovementRepository movements,
            GoalSavedQuery saved,
            AccountRepository accounts,
            UserRepository users,
            Clock clock) {
        this.goals = goals;
        this.movements = movements;
        this.saved = saved;
        this.accounts = accounts;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<GoalMovementResponse> list(UUID accountId, UUID goalId) {
        requireOwned(accountId, goalId);
        List<GoalMovement> rows = movements.findByGoalIdOrderByCreatedAtDescIdDesc(goalId);
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
                        m.getOccurredOn(),
                        m.getTransactionId() != null,
                        m.getNote(),
                        m.getCreatedBy() == null ? null : authorRef(userById.get(m.getCreatedBy())),
                        m.getCreatedAt()))
                .toList();
    }

    @Transactional
    public GoalResponse contribute(UUID accountId, UUID userId, UUID goalId, ContributeRequest request) {
        Goal goal = lockAndLoadActive(accountId, goalId);
        LocalDate today = LocalDate.now(clock);
        LocalDate date = request.date() == null ? today : request.date();
        YearMonth month = YearMonth.from(date);
        if (RunningMonths.isFuture(month, clock) || date.isAfter(today.plusDays(1))) {
            throw new ApiException(ErrorCode.GOAL_MONTH_INVALID);
        }
        requireMonthNotBeforeFirst(goal, month);
        long amount = request.amountCents();
        requireAmount(amount, remainingOf(goal, savedOf(goal)));
        GoalMovement movement = contribution(goal, amount, month, date, null, request.note(), userId);
        movements.saveAndFlush(movement);
        return GoalResponse.of(goal, savedOf(goal), null);
    }

    @Transactional
    public GoalResponse skip(UUID accountId, UUID userId, UUID goalId, SkipRequest request) {
        Goal goal = lockAndLoadActive(accountId, goalId);
        YearMonth month = request.month();
        if (RunningMonths.isFuture(month, clock)) {
            throw new ApiException(ErrorCode.GOAL_MONTH_INVALID);
        }
        requireMonthNotBeforeFirst(goal, month);
        LocalDate first = month.atDay(1);
        if (movements.existsByGoalIdAndMonthAndType(goal.getId(), first, GoalMovementType.SKIP)) {
            throw new ApiException(ErrorCode.GOAL_MONTH_TAKEN);
        }
        if (movements.existsByGoalIdAndMonthAndType(goal.getId(), first, GoalMovementType.CONTRIBUTION)) {
            throw new ApiException(ErrorCode.GOAL_MONTH_HAS_CONTRIBUTIONS);
        }
        return record(goal, userId, GoalMovementType.SKIP, 0L, month, first, null);
    }

    @Transactional
    public GoalResponse withdraw(UUID accountId, UUID userId, UUID goalId, GoalAmountRequest request) {
        Goal goal = lockAndLoadActive(accountId, goalId);
        long amount = request.amountCents();
        requireAmount(amount, savedOf(goal));
        return record(goal, userId, GoalMovementType.WITHDRAWAL, amount, null, LocalDate.now(clock), request.note());
    }

    /**
     * Records a movement that mirrors an imported transaction. The amount and date come from the file, so there is no
     * month, first-month or cap check; the caller passes the goal it loaded for the account it is importing into.
     */
    @Transactional
    public void recordImported(
            Goal goal, UUID userId, GoalMovementType type, long amountCents, LocalDate date, UUID transactionId) {
        if (goal.isCompleted()) {
            throw new ApiException(ErrorCode.GOAL_COMPLETED);
        }
        GoalMovement movement = type == GoalMovementType.CONTRIBUTION
                ? contribution(goal, amountCents, YearMonth.from(date), date, transactionId, null, userId)
                : new GoalMovement(
                        goal.getId(),
                        goal.getAccountId(),
                        type,
                        amountCents,
                        null,
                        date,
                        transactionId,
                        null,
                        userId,
                        Instant.now(clock));
        movements.saveAndFlush(movement);
    }

    /** A new contribution supersedes its month's skip, and remembers it did so the skip can come back. */
    private GoalMovement contribution(
            Goal goal,
            long amountCents,
            YearMonth month,
            LocalDate date,
            @Nullable UUID transactionId,
            @Nullable String note,
            UUID userId) {
        GoalMovement movement = new GoalMovement(
                goal.getId(),
                goal.getAccountId(),
                GoalMovementType.CONTRIBUTION,
                amountCents,
                month.atDay(1),
                date,
                transactionId,
                note == null || note.isBlank() ? null : note.strip(),
                userId,
                Instant.now(clock));
        movement.setReplacedSkip(removeSkip(goal.getId(), month.atDay(1)));
        return movement;
    }

    /**
     * The transaction a movement mirrors was edited: drop the movement unless the transaction is still a transfer, in
     * which case it takes the transaction's amount and date. A contribution that lands in a skipped month supersedes
     * the skip; a withdrawal is clamped so the goal's saved amount never goes below zero, and an edit that would take
     * the saved amount below zero (a contribution lowered or dropped after later withdrawals) is refused.
     */
    @Transactional
    public void followTransaction(
            UUID accountId, UUID transactionId, TransactionKind kind, long amountCents, LocalDate date) {
        if (movements.findByTransactionId(transactionId).isEmpty()) {
            return;
        }
        // Lock order: the account row first, then re-read the movement under the lock.
        accounts.findByIdForUpdate(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        movements.findByTransactionId(transactionId).ifPresent(movement -> {
            long before = saved.savedOf(movement.getGoalId());
            long withoutIt = before - effectOf(movement);
            boolean isWithdrawal = movement.getType() == GoalMovementType.WITHDRAWAL;
            // A withdrawal is capped by what the goal holds without it; a contribution follows the new amount.
            long newAmount = isWithdrawal ? Math.min(amountCents, withoutIt) : amountCents;
            if (kind != TransactionKind.TRANSFER || newAmount <= 0) {
                requireNotNegative(withoutIt);
                restoreSkips(List.of(movement));
                movements.delete(movement);
            } else {
                requireNotNegative(isWithdrawal ? withoutIt - newAmount : withoutIt + newAmount);
                boolean monthMoves = !isWithdrawal && !date.withDayOfMonth(1).equals(movement.getMonth());
                if (monthMoves) {
                    restoreSkips(List.of(movement));
                }
                movement.followTransaction(newAmount, date);
                if (monthMoves) {
                    movement.setReplacedSkip(removeSkip(movement.getGoalId(), movement.getMonth()));
                }
            }
            movements.flush();
        });
    }

    /**
     * A transaction is about to be deleted (its linked movement goes with it, by cascade): refuse if that movement is
     * a contribution the goal has spent, else put back the skip it replaced.
     */
    @Transactional
    public void beforeTransactionRemoved(UUID accountId, UUID transactionId) {
        if (movements.findByTransactionId(transactionId).isEmpty()) {
            return;
        }
        accounts.findByIdForUpdate(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        beforeRemoved(movements.findByTransactionId(transactionId).stream().toList());
    }

    /**
     * An import batch is about to be undone: refuse if any goal would end below zero without its linked movements,
     * else put back the skips they replaced. Takes the account lock, so the caller locks the batch after it.
     */
    @Transactional
    public void beforeBatchRemoved(UUID accountId, UUID batchId) {
        accounts.findByIdForUpdate(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        beforeRemoved(movements.findByImportBatch(accountId, batchId));
    }

    private void beforeRemoved(List<GoalMovement> removed) {
        Map<UUID, Long> effectByGoal = removed.stream()
                .collect(Collectors.groupingBy(GoalMovement::getGoalId, Collectors.summingLong(m -> effectOf(m))));
        effectByGoal.forEach((goalId, effect) -> requireNotNegative(saved.savedOf(goalId) - effect));
        restoreSkips(removed);
    }

    /**
     * {@code leaving} contributions are about to be removed or moved out of their month. A month whose skip one of
     * them replaced gets the skip back when no other contribution stays in it; when one stays, it takes over the claim.
     */
    private void restoreSkips(List<GoalMovement> leaving) {
        Set<UUID> leavingIds = leaving.stream().map(GoalMovement::getId).collect(Collectors.toSet());
        for (GoalMovement gone : leaving) {
            LocalDate month = gone.getMonth();
            if (!gone.replacedSkip() || month == null) continue;
            List<GoalMovement> staying =
                    movements
                            .findByGoalIdAndMonthAndType(gone.getGoalId(), month, GoalMovementType.CONTRIBUTION)
                            .stream()
                            .filter(m -> !leavingIds.contains(m.getId()))
                            .toList();
            if (!staying.isEmpty()) {
                staying.getFirst().setReplacedSkip(true);
            } else if (!movements.existsByGoalIdAndMonthAndType(gone.getGoalId(), month, GoalMovementType.SKIP)) {
                movements.save(new GoalMovement(
                        gone.getGoalId(),
                        gone.getAccountId(),
                        GoalMovementType.SKIP,
                        0L,
                        month,
                        month,
                        null,
                        null,
                        gone.getCreatedBy(),
                        Instant.now(clock)));
            }
        }
        movements.flush();
    }

    private static long effectOf(GoalMovement movement) {
        return switch (movement.getType()) {
            case CONTRIBUTION -> movement.getAmountCents();
            case WITHDRAWAL -> -movement.getAmountCents();
            case SKIP -> 0;
        };
    }

    private static void requireNotNegative(long saved) {
        if (saved < 0) {
            throw new ApiException(ErrorCode.GOAL_AMOUNT_INVALID);
        }
    }

    @Transactional
    public GoalResponse undo(UUID accountId, UUID goalId, UUID movementId) {
        Goal goal = lockAndLoadActive(accountId, goalId);
        GoalMovement movement = movements
                .findByIdAndGoalId(movementId, goalId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (movement.getTransactionId() != null) {
            throw new ApiException(ErrorCode.GOAL_MOVEMENT_IMPORTED);
        }
        long effect = movement.getType() == GoalMovementType.WITHDRAWAL
                ? -movement.getAmountCents()
                : movement.getAmountCents();
        long before = savedOf(goal);
        long after = before - effect;
        // Like a deposit, undoing a withdrawal may not take the goal above its target (lowered since, say);
        // an undo that brings the saved amount down is always allowed while it stays at or above zero.
        if (after < 0 || (after > before && after > goal.getTargetCents())) {
            throw new ApiException(ErrorCode.GOAL_AMOUNT_INVALID);
        }
        restoreSkips(List.of(movement));
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

    private void requireMonthNotBeforeFirst(Goal goal, YearMonth month) {
        if (month.isBefore(saved.firstMonthOf(goal))) {
            throw new ApiException(ErrorCode.GOAL_MONTH_INVALID);
        }
    }

    /** A contribution supersedes the month's skip; true when there was one. */
    private boolean removeSkip(UUID goalId, @Nullable LocalDate month) {
        return month != null && movements.deleteByGoalIdAndMonthAndType(goalId, month, GoalMovementType.SKIP) > 0;
    }

    private long savedOf(Goal goal) {
        return saved.savedOf(goal.getId());
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
            LocalDate occurredOn,
            @Nullable String note) {
        movements.saveAndFlush(new GoalMovement(
                goal.getId(),
                goal.getAccountId(),
                type,
                amount,
                month == null ? null : month.atDay(1),
                occurredOn,
                null,
                note == null || note.isBlank() ? null : note.strip(),
                userId,
                Instant.now(clock)));
        return GoalResponse.of(goal, savedOf(goal), null);
    }

    private static @Nullable AuthorRef authorRef(@Nullable User u) {
        return u == null ? null : new AuthorRef(u.getId(), u.getDisplayName());
    }
}
