package br.dev.duofinance.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RowFingerprintsTest {

    private static RevolutRow row(String state, LocalDateTime completedAt) {
        return new RevolutRow(2, "CARD_PAYMENT", "Current", LocalDateTime.of(2026, 1, 5, 10, 0), "2026-01-05 10:00:00",
                completedAt, "Lidl", -1250, "-12.50", 0, "EUR", state);
    }

    @Test
    void fingerprint_sameRowPendingThenCompleted_isEqual() {
        var pending = row("PENDING", null);
        var done = row("COMPLETED", LocalDateTime.of(2026, 1, 6, 9, 0));
        assertThat(RowFingerprints.of(List.of(pending))).isEqualTo(RowFingerprints.of(List.of(done)));
    }

    @Test
    void fingerprint_identicalRowsGetDistinctOccurrenceIndex() {
        var r = row("COMPLETED", LocalDateTime.of(2026, 1, 6, 9, 0));
        var first = RowFingerprints.of(List.of(r, r));
        assertThat(first).hasSize(2).doesNotHaveDuplicates();
        assertThat(RowFingerprints.of(List.of(r, r))).isEqualTo(first);
    }

    @Test
    void fee_differsFromRow() {
        var fp = RowFingerprints.of(List.of(row("COMPLETED", null))).get(0);
        assertThat(RowFingerprints.fee(fp)).hasSize(64).isNotEqualTo(fp);
    }
}
