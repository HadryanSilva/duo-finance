package br.dev.duofinance.goal.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A null salaryCategoryId clears the setting. */
public record UpdateGoalSettingsRequest(@Nullable UUID salaryCategoryId) {}
