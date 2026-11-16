package br.dev.duofinance.goal.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.goal.dto.GoalProjection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoalProjectionTest {

    private static final YearMonth MARCH = YearMonth.of(2027, 3);

    @Test
    void withDate_spreadsRemainingOverMonthsInclusive() {
        GoalProjection p = GoalProjections.of(100000, 40000, LocalDate.of(2027, 8, 20), List.of(), MARCH);

        assertThat(p).isNotNull();
        assertThat(p.requiredMonthlyCents()).isEqualTo(10000);
        assertThat(p.projectedMonth()).isNull();
        assertThat(p.targetDatePassed()).isFalse();
    }

    @Test
    void withDate_roundsRequiredUp() {
        GoalProjection p = GoalProjections.of(100001, 40000, LocalDate.of(2027, 8, 20), List.of(), MARCH);

        assertThat(p.requiredMonthlyCents()).isEqualTo(10001);
    }

    @Test
    void withDateInCurrentMonth_asksForRemainingNow() {
        GoalProjection p = GoalProjections.of(100000, 40000, LocalDate.of(2027, 3, 31), List.of(), MARCH);

        assertThat(p.requiredMonthlyCents()).isEqualTo(60000);
        assertThat(p.targetDatePassed()).isFalse();
    }

    @Test
    void withPastDate_flagsPassed_andFallsBackToPace() {
        GoalProjection p =
                GoalProjections.of(120000, 60000, LocalDate.of(2027, 1, 10), List.of(30000L, 20000L, 10000L), MARCH);

        assertThat(p).isNotNull();
        assertThat(p.targetDatePassed()).isTrue();
        assertThat(p.requiredMonthlyCents()).isNull();
        assertThat(p.projectedMonth()).isEqualTo(YearMonth.of(2027, 6));
    }

    @Test
    void withPastDate_noContributions_flagsPassedOnly() {
        GoalProjection p = GoalProjections.of(100000, 40000, LocalDate.of(2027, 2, 28), List.of(), MARCH);

        assertThat(p).isNotNull();
        assertThat(p.targetDatePassed()).isTrue();
        assertThat(p.requiredMonthlyCents()).isNull();
        assertThat(p.projectedMonth()).isNull();
    }

    @Test
    void withoutDate_usesAverageOfLastThree() {
        GoalProjection p = GoalProjections.of(120000, 60000, null, List.of(30000L, 20000L, 10000L), MARCH);

        assertThat(p).isNotNull();
        assertThat(p.projectedMonth()).isEqualTo(YearMonth.of(2027, 6));
        assertThat(p.requiredMonthlyCents()).isNull();
    }

    @Test
    void withoutDate_noContributions_null() {
        assertThat(GoalProjections.of(120000, 60000, null, List.of(), MARCH)).isNull();
    }

    @Test
    void reached_null() {
        assertThat(GoalProjections.of(100000, 100000, LocalDate.of(2027, 8, 1), List.of(5L), MARCH))
                .isNull();
        assertThat(GoalProjections.of(100000, 120000, null, List.of(5L), MARCH)).isNull();
    }
}
