package br.dev.duofinance.imports.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fingerprints of statement rows an import saw without recording anything (a pocket withdrawal from an empty goal).
 * They count as already imported, so a later overlapping statement does not apply them; undoing the batch forgets
 * them.
 */
@Component
public class ImportSeenRows {

    private final JdbcTemplate jdbc;

    public ImportSeenRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void remember(UUID accountId, String fingerprint, UUID batchId) {
        jdbc.update(
                "INSERT INTO import_seen_rows (account_id, fingerprint, import_batch_id) VALUES (?, ?, ?)"
                        + " ON CONFLICT DO NOTHING",
                accountId,
                fingerprint,
                batchId);
    }

    public void forgetBatch(UUID accountId, UUID batchId) {
        jdbc.update(
                "DELETE FROM import_seen_rows WHERE account_id = ? AND import_batch_id = ?", accountId, batchId);
    }
}
