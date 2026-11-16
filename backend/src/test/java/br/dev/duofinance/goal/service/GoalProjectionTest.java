package br.dev.duofinance.goal.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.goal.dto.GoalProjection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoalProjectionTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 3, 15);
    private static final boolean PENDING = false;
    private static final boolean DECIDED = true;

    @Test
    void withDate_spreadsRemainingOverMonthsInclusive() {
        GoalProjection p = GoalProjections.of(100000, 40000, LocalDate.of(2027, 8, 20), List.of(), TODAY, PENDING);

        assertThat(p).isNotNull();
        assertThat(p.requiredMonthlyCents()).isEqualTo(10000);
        assertThat(p.projectedMonth()).isNull();
        assertThat(p.targetDatePassed()).isFalse();
    }

    @Test
    void withDate_thisMonthAlreadyDecided_spreadsOverTheMonthsAfter() {
        GoalProjection p = GoalProjections.of(100000, 50000, LocalDate.of(2027, 8, 20), List.of(), TODAY, DECIDED);

        assertThat(p.requiredMonthlyCents()).isEqualTo(10000);
    }

    @Test
    void withDate_roundsRequiredUp() {
        GoalProjection p = GoalProjections.of(100001, 40000, LocalDate.of(2027, 8, 20), List.of(), TODAY, PENDING);

        assertThat(p.requiredMonthlyCents()).isEqualTo(10001);
    }

    @Test
    void withDateLaterInCurrentMonth_asksForRemainingNow() {
        GoalProjection p = GoalProjections.of(100000, 40000, LocalDate.of(2027, 3, 31), List.of(), TODAY, PENDING);

        assertThat(p.requiredMonthlyCents()).isEqualTo(60000);
        assertThat(p.targetDatePassed()).isFalse();
    }

    @Test
    void withDateLaterInCurrentMonth_alreadyDecided_stillAsksForRemainingNow() {
        GoalProjection p = GoalProjections.of(100000, 40000, LocalDate.of(2027, 3, 31), List.of(), TODAY, DECIDED);

        assertThat(p.requiredMonthlyCents()).isEqualTo(60000);
        assertThat(p.targetDatePassed()).isFalse();
    }

    @Test
    void withDateToday_isNotPassed() {
        GoalProjection p = GoalProjections.of(100000, 40000, TODAY, List.of(), TODAY, PENDING);

        assertThat(p.targetDatePassed()).isFalse();
        assertThat(p.requiredMonthlyCents()).isEqualTo(60000);
    }

    @Test
    void withDateEarlierInCurrentMonth_flagsPassed() {
        GoalProjection p =
                GoalProjections.of(100000, 40000, LocalDate.of(2027, 3, 14), List.of(20000L), TODAY, DECIDED);

        assertThat(p).isNotNull();
        assertThat(p.targetDatePassed()).isTrue();
        assertThat(p.requiredMonthlyCents()).isNull();
        assertThat(p.projectedMonth()).isEqualTo(YearMonth.of(2027, 6));
    }

    @Test
    void withPastDate_flagsPassed_andFallsBackToPace() {
        GoalProjection p = GoalProjections.of(
                120000, 60000, LocalDate.of(2027, 1, 10), List.of(30000L, 20000L, 10000L), TODAY, DECIDED);

        assertThat(p).isNotNull();
        assertThat(p.targetDatePassed()).isTrue();
        assertThat(p.requiredMonthlyCents()).isNull();
        assertThat(p.projectedMonth()).isEqualTo(YearMonth.of(2027, 6));
    }

    @Test
    void withPastDate_noContributions_flagsPassedOnly() {
        GoalProjection p = GoalProjections.of(100000, 40000, LocalDate.of(2027, 2, 28), List.of(), TODAY, PENDING);

        assertThat(p).isNotNull();
        assertThat(p.targetDatePassed()).isTrue();
        assertThat(p.requiredMonthlyCents()).isNull();
        assertThat(p.projectedMonth()).isNull();
    }

    @Test
    void withoutDate_thisMonthDecided_paceStartsNextMonth() {
        GoalProjection p = GoalProjections.of(120000, 60000, null, List.of(30000L, 20000L, 10000L), TODAY, DECIDED);

        assertThat(p).isNotNull();
        // Average 20000, 3 contributions left: April, May, June.
        assertThat(p.projectedMonth()).isEqualTo(YearMonth.of(2027, 6));
        assertThat(p.requiredMonthlyCents()).isNull();
    }

    @Test
    void withoutDate_thisMonthPending_paceCountsThisMonth() {
        GoalProjection p = GoalProjections.of(120000, 60000, null, List.of(30000L, 20000L, 10000L), TODAY, PENDING);

        // 3 contributions left: March, April, May.
        assertThat(p.projectedMonth()).isEqualTo(YearMonth.of(2027, 5));
    }

    @Test
    void withoutDate_noContributions_null() {
        assertThat(GoalProjections.of(120000, 60000, null, List.of(), TODAY, PENDING))
                .isNull();
    }

    @Test
    void reached_null() {
        assertThat(GoalProjections.of(100000, 100000, LocalDate.of(2027, 8, 1), List.of(5L), TODAY, PENDING))
                .isNull();
        assertThat(GoalProjections.of(100000, 120000, null, List.of(5L), TODAY, PENDING))
                .isNull();
    }
}
