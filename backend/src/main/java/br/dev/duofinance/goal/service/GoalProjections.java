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
     * A target month already behind the current one cannot be reached any more: the projection then says so and
     * falls back to the current pace, when there is one.
     *
     * @param lastContributions the goal's last contribution amounts, newest first, at most three
     */
    static @Nullable GoalProjection of(
            long targetCents,
            long savedCents,
            @Nullable LocalDate targetDate,
            List<Long> lastContributions,
            YearMonth current) {
        long remaining = targetCents - savedCents;
        if (remaining <= 0) {
            return null;
        }
        if (targetDate != null) {
            YearMonth targetMonth = YearMonth.from(targetDate);
            if (targetMonth.isBefore(current)) {
                return new GoalProjection(null, paceMonth(remaining, lastContributions, current), true);
            }
            long months = ChronoUnit.MONTHS.between(current, targetMonth) + 1;
            return new GoalProjection(Math.ceilDiv(remaining, months), null, false);
        }
        YearMonth pace = paceMonth(remaining, lastContributions, current);
        return pace == null ? null : new GoalProjection(null, pace, false);
    }

    /** The month the average of the last contributions reaches the target; null without a positive average. */
    private static @Nullable YearMonth paceMonth(long remaining, List<Long> lastContributions, YearMonth current) {
        if (lastContributions.isEmpty()) {
            return null;
        }
        long average = Math.ceilDiv(
                lastContributions.stream().mapToLong(Long::longValue).sum(), lastContributions.size());
        if (average <= 0) {
            return null;
        }
        return current.plusMonths(Math.ceilDiv(remaining, average));
    }
}
