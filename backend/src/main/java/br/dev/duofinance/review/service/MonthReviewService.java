package br.dev.duofinance.review.service;

import br.dev.duofinance.review.dto.MonthReviewResponse;
import br.dev.duofinance.review.dto.MonthReviewResponse.CategoryLine;
import br.dev.duofinance.review.dto.MonthReviewResponse.Figure;
import br.dev.duofinance.review.repository.MonthReviewQuery;
import br.dev.duofinance.review.repository.MonthReviewQuery.CategorySpend;
import br.dev.duofinance.review.repository.MonthReviewQuery.Meta;
import br.dev.duofinance.review.repository.MonthReviewQuery.MonthTotals;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.time.RunningMonths;
import java.time.Clock;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A closed month against the average of the up to 3 months before it that the household has data for. */
@Service
public class MonthReviewService {

    static final int WINDOW_MONTHS = 3;
    private static final MonthTotals NONE = new MonthTotals(0, 0);

    private final MonthReviewQuery query;
    private final Clock clock;

    public MonthReviewService(MonthReviewQuery query, Clock clock) {
        this.query = query;
        this.clock = clock;
    }

    /** The latest month that is over everywhere on Earth. */
    public YearMonth lastClosedMonth() {
        return RunningMonths.earliest(clock).minusMonths(1);
    }

    @Transactional(readOnly = true)
    public MonthReviewResponse review(UUID accountId, YearMonth month) {
        YearMonth lastClosed = lastClosedMonth();
        if (month.isAfter(lastClosed)) {
            throw new ApiException(ErrorCode.MONTH_NOT_CLOSED);
        }
        Meta meta = query.meta(accountId);
        YearMonth first = meta.firstMonth();
        // Months before the first transaction predate the app's use: they would drag the average down with zeros.
        YearMonth windowStart = month.minusMonths(WINDOW_MONTHS);
        if (first != null && windowStart.isBefore(first)) {
            windowStart = first;
        }
        int compared = first == null || month.isBefore(first)
                ? 0
                : (int) Math.max(0, ChronoUnit.MONTHS.between(windowStart, month));

        YearMonth from = compared == 0 ? month : windowStart;
        Map<YearMonth, MonthTotals> totals = query.monthlyTotals(accountId, from, month);
        List<CategorySpend> spending = query.monthlySpending(accountId, from, month);

        MonthTotals current = totals.getOrDefault(month, NONE);
        long incomeSum = 0;
        long expenseSum = 0;
        for (Map.Entry<YearMonth, MonthTotals> e : totals.entrySet()) {
            if (e.getKey().isBefore(month)) {
                incomeSum += e.getValue().incomeCents();
                expenseSum += e.getValue().expenseCents();
            }
        }
        Long incomeAvg = average(incomeSum, compared);
        Long expenseAvg = average(expenseSum, compared);
        Long leftAvg = incomeAvg == null || expenseAvg == null ? null : incomeAvg - expenseAvg;

        return new MonthReviewResponse(
                month,
                meta.currency(),
                first,
                lastClosed,
                compared,
                new Figure(current.incomeCents(), incomeAvg),
                new Figure(current.expenseCents(), expenseAvg),
                new Figure(current.incomeCents() - current.expenseCents(), leftAvg),
                lines(spending, month, compared));
    }

    private static List<CategoryLine> lines(List<CategorySpend> spending, YearMonth month, int compared) {
        Map<UUID, CategorySpend> details = new LinkedHashMap<>();
        Map<UUID, long[]> sums = new LinkedHashMap<>(); // [month, window]
        for (CategorySpend s : spending) {
            details.putIfAbsent(s.categoryId(), s);
            long[] pair = sums.computeIfAbsent(s.categoryId(), id -> new long[2]);
            pair[s.month().equals(month) ? 0 : 1] += s.cents();
        }
        List<CategoryLine> result = new ArrayList<>();
        details.forEach((id, d) -> {
            long[] pair = sums.get(id);
            result.add(new CategoryLine(
                    id, d.name(), d.color(), d.icon(), d.archived(), pair[0], average(pair[1], compared)));
        });
        result.sort(Comparator.comparing(CategoryLine::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CategoryLine::categoryId));
        return result;
    }

    /** Half-up to a whole cent; the sums are never negative. Null without months to compare with. */
    static @Nullable Long average(long sum, int months) {
        return months == 0 ? null : (2 * sum + months) / (2L * months);
    }
}
