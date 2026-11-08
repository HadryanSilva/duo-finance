package br.dev.duofinance.recurring.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.recurring.model.Frequency;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RecurrenceScheduleTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void monthlyOn31st_clampsAndRecovers() {
        LocalDate start = d("2027-01-31");
        assertThat(RecurrenceSchedule.occurrence(start, Frequency.MONTHLY, 0)).isEqualTo(d("2027-01-31"));
        assertThat(RecurrenceSchedule.occurrence(start, Frequency.MONTHLY, 1)).isEqualTo(d("2027-02-28"));
        assertThat(RecurrenceSchedule.occurrence(start, Frequency.MONTHLY, 2)).isEqualTo(d("2027-03-31"));
        assertThat(RecurrenceSchedule.occurrence(start, Frequency.MONTHLY, 3)).isEqualTo(d("2027-04-30"));
        assertThat(RecurrenceSchedule.occurrence(d("2028-01-31"), Frequency.MONTHLY, 1))
                .isEqualTo(d("2028-02-29"));

        assertThat(RecurrenceSchedule.between(start, Frequency.MONTHLY, null, d("2027-01-01"), d("2027-05-31")))
                .containsExactly(d("2027-01-31"), d("2027-02-28"), d("2027-03-31"), d("2027-04-30"), d("2027-05-31"));
    }

    @Test
    void yearlyOnLeapDay_clamps() {
        LocalDate start = d("2028-02-29");
        assertThat(RecurrenceSchedule.occurrence(start, Frequency.YEARLY, 1)).isEqualTo(d("2029-02-28"));
        assertThat(RecurrenceSchedule.occurrence(start, Frequency.YEARLY, 2)).isEqualTo(d("2030-02-28"));
        assertThat(RecurrenceSchedule.occurrence(start, Frequency.YEARLY, 4)).isEqualTo(d("2032-02-29"));
        assertThat(RecurrenceSchedule.firstOnOrAfter(start, Frequency.YEARLY, d("2029-03-01")))
                .isEqualTo(d("2030-02-28"));
    }

    @Test
    void weekly() {
        LocalDate start = d("2027-03-01");
        assertThat(RecurrenceSchedule.occurrence(start, Frequency.WEEKLY, 1)).isEqualTo(d("2027-03-08"));
        assertThat(RecurrenceSchedule.occurrence(start, Frequency.WEEKLY, 5)).isEqualTo(d("2027-04-05"));
        assertThat(RecurrenceSchedule.firstOnOrAfter(start, Frequency.WEEKLY, d("2027-03-09")))
                .isEqualTo(d("2027-03-15"));
        assertThat(RecurrenceSchedule.firstOnOrAfter(start, Frequency.WEEKLY, d("2027-03-15")))
                .isEqualTo(d("2027-03-15"));
        assertThat(RecurrenceSchedule.between(start, Frequency.WEEKLY, null, d("2027-03-02"), d("2027-03-22")))
                .containsExactly(d("2027-03-08"), d("2027-03-15"), d("2027-03-22"));
    }

    @Test
    void firstOnOrAfter_returnsStartWhenDateBeforeStart() {
        LocalDate start = d("2027-01-31");
        assertThat(RecurrenceSchedule.firstOnOrAfter(start, Frequency.MONTHLY, d("2026-06-01")))
                .isEqualTo(start);
        assertThat(RecurrenceSchedule.firstOnOrAfter(start, Frequency.MONTHLY, start))
                .isEqualTo(start);
        assertThat(RecurrenceSchedule.firstOnOrAfter(start, Frequency.MONTHLY, d("2027-02-01")))
                .isEqualTo(d("2027-02-28"));
        assertThat(RecurrenceSchedule.firstOnOrAfter(start, Frequency.MONTHLY, d("2027-02-28")))
                .isEqualTo(d("2027-02-28"));
        assertThat(RecurrenceSchedule.firstOnOrAfter(start, Frequency.MONTHLY, d("2027-03-01")))
                .isEqualTo(d("2027-03-31"));
    }

    @Test
    void between_respectsEndDate() {
        LocalDate start = d("2027-01-31");
        assertThat(RecurrenceSchedule.between(
                        start, Frequency.MONTHLY, d("2027-03-30"), d("2027-01-01"), d("2027-12-31")))
                .containsExactly(d("2027-01-31"), d("2027-02-28"));
        assertThat(RecurrenceSchedule.between(
                        start, Frequency.MONTHLY, d("2027-03-31"), d("2027-02-01"), d("2027-12-31")))
                .containsExactly(d("2027-02-28"), d("2027-03-31"));
        assertThat(RecurrenceSchedule.between(start, Frequency.MONTHLY, null, d("2027-03-01"), d("2027-02-01")))
                .isEmpty();
        assertThat(RecurrenceSchedule.between(start, Frequency.MONTHLY, null, d("2026-01-01"), d("2027-01-30")))
                .isEmpty();
    }
}
