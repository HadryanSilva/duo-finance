package br.dev.duofinance.review.dto;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A closed month next to the average of the (up to) 3 months before it, counting only months since the household's
 * first transaction. {@code comparedMonths} is how many months the averages cover; with 0, every average is null.
 * {@code firstMonth} is null when the household has no transactions.
 */
public record MonthReviewResponse(
        YearMonth month,
        String currency,
        @Nullable YearMonth firstMonth,
        YearMonth lastClosedMonth,
        int comparedMonths,
        Figure income,
        Figure expense,
        Figure left,
        List<CategoryLine> categories) {

    /** For {@code left}, the average is the income average minus the expense average. */
    public record Figure(long cents, @Nullable Long averageCents) {}

    /** Ordered by name: the client orders the lines by how much they changed. */
    public record CategoryLine(
            UUID categoryId,
            String name,
            String color,
            String icon,
            boolean archived,
            long cents,
            @Nullable Long averageCents) {}
}
