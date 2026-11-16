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

    /** @param lastContributions the goal's last contribution amounts, newest first, at most three */
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
            long months = Math.max(1, ChronoUnit.MONTHS.between(current, YearMonth.from(targetDate)) + 1);
            return new GoalProjection(Math.ceilDiv(remaining, months), null);
        }
        if (lastContributions.isEmpty()) {
            return null;
        }
        long average = Math.ceilDiv(
                lastContributions.stream().mapToLong(Long::longValue).sum(), lastContributions.size());
        if (average <= 0) {
            return null;
        }
        return new GoalProjection(null, current.plusMonths(Math.ceilDiv(remaining, average)));
    }
}
