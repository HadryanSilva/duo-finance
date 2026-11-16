package br.dev.duofinance.goal.service;

import br.dev.duofinance.goal.dto.GoalProjection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Pure projection maths for a goal that is not yet reached. */
final class GoalProjections {

    private GoalProjections() {}

    /**
     * A target date before today cannot be reached any more: the projection then says so and falls back to the
     * current pace, when there is one. Contributions are counted from the current month while its contribution is
     * still pending, and from the next month once it was decided (contributed or skipped).
     *
     * @param lastContributions the goal's last contribution amounts, newest first, at most three
     * @param decidedThisMonth whether the goal already has a contribution or a skip for the current month
     */
    static @Nullable GoalProjection of(
            long targetCents,
            long savedCents,
            @Nullable LocalDate targetDate,
            List<Long> lastContributions,
            LocalDate today,
            boolean decidedThisMonth) {
        long remaining = targetCents - savedCents;
        if (remaining <= 0) {
            return null;
        }
        YearMonth current = YearMonth.from(today);
        YearMonth firstMonth = decidedThisMonth ? current.plusMonths(1) : current;
        if (targetDate != null) {
            if (targetDate.isBefore(today)) {
                return new GoalProjection(null, paceMonth(remaining, lastContributions, firstMonth), true);
            }
            // A target later this month with this month already decided leaves one month: the remaining now.
            long months = Math.max(1, ChronoUnit.MONTHS.between(firstMonth, YearMonth.from(targetDate)) + 1);
            return new GoalProjection(Math.ceilDiv(remaining, months), null, false);
        }
        YearMonth pace = paceMonth(remaining, lastContributions, firstMonth);
        return pace == null ? null : new GoalProjection(null, pace, false);
    }

    /**
     * The month the average of the last contributions reaches the target, contributing from {@code firstMonth} on;
     * null without a positive average.
     */
    private static @Nullable YearMonth paceMonth(long remaining, List<Long> lastContributions, YearMonth firstMonth) {
        if (lastContributions.isEmpty()) {
            return null;
        }
        long average = Math.ceilDiv(
                lastContributions.stream().mapToLong(Long::longValue).sum(), lastContributions.size());
        if (average <= 0) {
            return null;
        }
        return firstMonth.plusMonths(Math.ceilDiv(remaining, average) - 1);
    }
}
