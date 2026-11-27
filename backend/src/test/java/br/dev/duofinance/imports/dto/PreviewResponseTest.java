package br.dev.duofinance.imports.dto;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.imports.service.ImportPlan;
import br.dev.duofinance.imports.service.PlannedRow;
import br.dev.duofinance.imports.service.RowStatus;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreviewResponseTest {

    private static PlannedRow row(String id, String startedAt, int line) {
        LocalDateTime started = LocalDateTime.parse(startedAt);
        return new PlannedRow(
                id,
                started.toLocalDate(),
                TransactionKind.EXPENSE,
                100,
                id,
                id,
                UUID.randomUUID(),
                null,
                null,
                RowStatus.NEW,
                null,
                started,
                line);
    }

    /** The commit applies rows by statement time, then file line; the preview tells the review that order. */
    @Test
    void of_numbersRowsInCommitOrder_keepingFileOrder() {
        List<PlannedRow> rows = List.of(
                row("late", "2026-11-26T14:55:30", 2),
                row("early", "2026-11-26T14:34:39", 5),
                row("sameTimeLaterLine", "2026-11-26T14:34:39", 7));

        PreviewResponse preview = PreviewResponse.of(new ImportPlan(List.of(), rows, List.of()), Map.of());

        assertThat(preview.rows())
                .extracting(PreviewResponse.PreviewRow::rowId)
                .containsExactly("late", "early", "sameTimeLaterLine");
        assertThat(preview.rows())
                .extracting(PreviewResponse.PreviewRow::applyOrder)
                .containsExactly(2, 0, 1);
    }

    @Test
    void of_emptyPlan_noRows() {
        assertThat(PreviewResponse.of(new ImportPlan(List.of(), List.of(), List.of()), Map.of())
                        .rows())
                .isEmpty();
    }
}
