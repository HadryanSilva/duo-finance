package br.dev.duofinance.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * The server runs on UTC but the couple lives in a time zone of its own, which the server does not know: around
 * midnight on the 1st, the month on their screen can be one ahead of (or behind) the UTC month. A month counts as
 * running while it is the current month somewhere on Earth, from UTC-12 to UTC+14.
 */
public final class RunningMonths {

    private RunningMonths() {}

    /** The latest month already begun somewhere. */
    public static YearMonth latest(Clock clock) {
        return YearMonth.from(clock.instant().atOffset(ZoneOffset.MAX));
    }

    /** The earliest month still running somewhere. */
    public static YearMonth earliest(Clock clock) {
        return YearMonth.from(clock.instant().atOffset(ZoneOffset.MIN));
    }

    /** A month that has not begun anywhere yet. */
    public static boolean isFuture(YearMonth month, Clock clock) {
        return month.isAfter(latest(clock));
    }

    /** A month that is the current one somewhere. */
    public static boolean isRunning(YearMonth month, Clock clock) {
        return !month.isBefore(earliest(clock)) && !isFuture(month, clock);
    }

    /** The earliest month the instant falls in, anywhere: a goal created then exists from that month on. */
    public static YearMonth earliestOf(Instant instant) {
        return YearMonth.from(instant.atOffset(ZoneOffset.MIN));
    }
}
