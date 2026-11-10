package br.dev.duofinance.imports.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The user's choice for one previewed row; amounts, dates and kinds always come from the file. */
public record RowDecision(String rowId, boolean include, @Nullable UUID categoryId) {}
