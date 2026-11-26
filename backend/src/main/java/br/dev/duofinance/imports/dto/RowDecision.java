package br.dev.duofinance.imports.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The user's choice for one previewed row; amounts, dates and kinds always come from the file. {@code goalId} is the
 * goal a pocket move contributes to or withdraws from, null for none.
 */
public record RowDecision(String rowId, boolean include, @Nullable UUID categoryId, @Nullable UUID goalId) {}
