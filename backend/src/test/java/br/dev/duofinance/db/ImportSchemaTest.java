package br.dev.duofinance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class ImportSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, name, created_at, updated_at) VALUES (?, 'A', now(), now())", id);
        return id;
    }

    private UUID category(UUID account) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO categories (id, account_id, name, kind, color, icon, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'EXPENSE', '#112233', 'tag', now(), now())",
                id,
                account,
                "cat-" + id);
        return id;
    }

    private UUID bank(UUID account) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO bank_accounts (id, account_id, name, type, initial_balance_cents, created_at,"
                        + " updated_at) VALUES (?, ?, 'B', 'CHECKING', 0, now(), now())",
                id,
                account);
        return id;
    }

    private UUID batch(UUID account, UUID bank) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO import_batches (id, account_id, source, bank_account_id, row_count, created_at)"
                        + " VALUES (?, ?, 'REVOLUT', ?, 1, now())",
                id,
                account,
                bank);
        return id;
    }

    private UUID tx(UUID account, UUID bank, UUID batch, String fingerprint) {
        UUID category = category(account);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO transactions (id, account_id, kind, amount_cents, date, category_id, bank_account_id,"
                        + " import_batch_id, import_fingerprint, created_at, updated_at)"
                        + " VALUES (?, ?, 'EXPENSE', 100, '2026-01-01', ?, ?, ?, ?, now(), now())",
                id,
                account,
                category,
                bank,
                batch,
                fingerprint);
        return id;
    }

    @Test
    void fingerprintIsUniquePerAccount() {
        UUID acc = account();
        UUID bank = bank(acc);
        tx(acc, bank, null, "fp-1");

        assertThatThrownBy(() -> tx(acc, bank, null, "fp-1")).isInstanceOf(DuplicateKeyException.class);

        UUID other = account();
        tx(other, bank(other), null, "fp-1");

        tx(acc, bank, null, null);
        tx(acc, bank, null, null);
    }

    @Test
    void deletingBatchNullsTransactionBatchId() {
        UUID acc = account();
        UUID bank = bank(acc);
        UUID batch = batch(acc, bank);
        UUID tx = tx(acc, bank, batch, "fp-1");

        jdbc.update("DELETE FROM import_batches WHERE id = ?", batch);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE id = ?", Integer.class, tx))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT import_batch_id FROM transactions WHERE id = ?", UUID.class, tx))
                .isNull();
    }

    @Test
    void categoryMemoryCascadesWithCategory() {
        UUID acc = account();
        UUID cat = category(acc);
        jdbc.update(
                "INSERT INTO category_memory (account_id, description_key, kind, category_id, updated_at)"
                        + " VALUES (?, 'uber', 'EXPENSE', ?, now())",
                acc,
                cat);

        jdbc.update("DELETE FROM categories WHERE id = ?", cat);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM category_memory WHERE account_id = ?", Integer.class, acc))
                .isZero();
    }

    @Test
    void deletingBankAccountNullsBatchBankAccountId() {
        UUID acc = account();
        UUID bank = bank(acc);
        UUID batch = batch(acc, bank);

        assertThat(jdbc.update("DELETE FROM bank_accounts WHERE id = ?", bank)).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT bank_account_id FROM import_batches WHERE id = ?", UUID.class, batch))
                .isNull();
    }

    @Test
    void categoryMemory_hasCategoryIdIndex() {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE tablename = 'category_memory'"
                                + " AND indexname = 'idx_category_memory_category_id'",
                        Integer.class))
                .isEqualTo(1);
    }
}
