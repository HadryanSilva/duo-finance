package br.dev.duofinance.goal.dto;

import java.time.YearMonth;
import java.util.UUID;

public record GoalSuggestion(UUID goalId, String goalName, YearMonth month, int monthlyPercent, long suggestedCents) {}
