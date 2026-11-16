package br.dev.duofinance.goal.dto;

import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

public record GoalProjection(
        @Nullable Long requiredMonthlyCents, @Nullable YearMonth projectedMonth, boolean targetDatePassed) {}
