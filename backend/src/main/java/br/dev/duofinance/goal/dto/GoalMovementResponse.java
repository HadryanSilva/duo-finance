package br.dev.duofinance.goal.dto;

import br.dev.duofinance.goal.model.GoalMovementType;
import br.dev.duofinance.transaction.dto.TransactionResponse.AuthorRef;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record GoalMovementResponse(
        UUID id,
        GoalMovementType type,
        long amountCents,
        @Nullable YearMonth month,
        @Nullable String note,
        @Nullable AuthorRef createdBy,
        Instant createdAt) {}
