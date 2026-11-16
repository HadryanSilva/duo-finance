package br.dev.duofinance.goal.dto;

import br.dev.duofinance.transaction.dto.TransactionResponse.CategoryRef;
import org.jspecify.annotations.Nullable;

public record GoalSettingsResponse(@Nullable CategoryRef salaryCategory) {}
