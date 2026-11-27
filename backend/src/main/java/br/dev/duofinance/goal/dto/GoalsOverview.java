package br.dev.duofinance.goal.dto;

import br.dev.duofinance.transaction.dto.TransactionResponse.CategoryRef;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record GoalsOverview(
        YearMonth month,
        @Nullable CategoryRef salaryCategory,
        long salaryCents,
        long totalSavedCents,
        List<GoalResponse> goals,
        List<GoalResponse> completed,
        List<GoalMonthTarget> monthTargets) {}
