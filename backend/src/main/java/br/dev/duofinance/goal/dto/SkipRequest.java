package br.dev.duofinance.goal.dto;

import jakarta.validation.constraints.NotNull;
import java.time.YearMonth;

public record SkipRequest(@NotNull YearMonth month) {}
