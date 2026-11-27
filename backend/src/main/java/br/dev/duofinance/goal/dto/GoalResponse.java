package br.dev.duofinance.goal.dto;

import br.dev.duofinance.goal.model.Goal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record GoalResponse(
        UUID id,
        String name,
        long targetCents,
        @Nullable LocalDate targetDate,
        int monthlyPercent,
        GoalStatus status,
        long savedCents,
        long remainingCents,
        @Nullable Instant completedAt,
        Instant createdAt,
        @Nullable GoalProjection projection,
        @Nullable String revolutPocket) {

    public static GoalResponse of(Goal goal, long savedCents, @Nullable GoalProjection projection) {
        GoalStatus status = goal.isCompleted()
                ? GoalStatus.COMPLETED
                : savedCents >= goal.getTargetCents() ? GoalStatus.REACHED : GoalStatus.ACTIVE;
        return new GoalResponse(
                goal.getId(),
                goal.getName(),
                goal.getTargetCents(),
                goal.getTargetDate(),
                goal.getMonthlyPercent(),
                status,
                savedCents,
                Math.max(goal.getTargetCents() - savedCents, 0),
                goal.getCompletedAt(),
                goal.getCreatedAt(),
                projection,
                goal.getRevolutPocket());
    }
}
