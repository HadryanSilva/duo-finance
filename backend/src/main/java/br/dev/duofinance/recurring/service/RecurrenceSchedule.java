package br.dev.duofinance.recurring.service;

import br.dev.duofinance.recurring.model.Frequency;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Occurrence {@code k} is always {@code start} plus {@code k} periods, never chained from the previous occurrence, so a
 * monthly rule on the 31st falls on the last day of short months and returns to the 31st afterwards.
 */
public final class RecurrenceSchedule {

    private RecurrenceSchedule() {}

    public static LocalDate occurrence(LocalDate start, Frequency frequency, int k) {
        return switch (frequency) {
            case WEEKLY -> start.plusWeeks(k);
            case MONTHLY -> start.plusMonths(k);
            case YEARLY -> start.plusYears(k);
        };
    }

    /** The earliest occurrence on or after {@code date}; {@code start} when {@code date <= start}. */
    public static LocalDate firstOnOrAfter(LocalDate start, Frequency frequency, LocalDate date) {
        return occurrence(start, frequency, firstIndexOnOrAfter(start, frequency, date));
    }

    /** Occurrences within {@code [from, to]} that do not pass {@code endDate} (nullable), in order. */
    public static List<LocalDate> between(
            LocalDate start, Frequency frequency, LocalDate endDate, LocalDate from, LocalDate to) {
        LocalDate last = endDate != null && endDate.isBefore(to) ? endDate : to;
        List<LocalDate> dates = new ArrayList<>();
        for (int k = firstIndexOnOrAfter(start, frequency, from); ; k++) {
            LocalDate date = occurrence(start, frequency, k);
            if (date.isAfter(last)) {
                return dates;
            }
            dates.add(date);
        }
    }

    private static int firstIndexOnOrAfter(LocalDate start, Frequency frequency, LocalDate date) {
        if (!date.isAfter(start)) {
            return 0;
        }
        // Whole periods elapsed is a lower bound; clamping to a short month can leave that occurrence before date.
        int k = (int) unit(frequency).between(start, date);
        while (occurrence(start, frequency, k).isBefore(date)) {
            k++;
        }
        return k;
    }

    private static ChronoUnit unit(Frequency frequency) {
        return switch (frequency) {
            case WEEKLY -> ChronoUnit.WEEKS;
            case MONTHLY -> ChronoUnit.MONTHS;
            case YEARLY -> ChronoUnit.YEARS;
        };
    }
}
