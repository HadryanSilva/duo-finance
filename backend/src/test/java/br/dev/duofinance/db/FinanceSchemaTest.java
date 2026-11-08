package br.dev.duofinance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.IntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class FinanceSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, name, created_at, updated_at) VALUES (?, 'A', now(), now())", id);
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, display_name, locale, terms_version, terms_accepted_at, created_at,"
                        + " updated_at) VALUES (?, ?, 'U', 'en', 'v1', now(), now(), now())",
                id,
                id + "@mail.com");
        return id;
    }

    private UUID category(UUID account, String name, String kind) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO categories (id, account_id, name, kind, color, icon, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, '#112233', 'tag', now(), now())",
                id,
                account,
                name,
                kind);
        return id;
    }

    private UUID bank(UUID account, UUID holder) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO bank_accounts (id, account_id, name, type, initial_balance_cents, holder_user_id,"
                        + " created_at, updated_at) VALUES (?, ?, 'B', 'CHECKING', 0, ?, now(), now())",
                id,
                account,
                holder);
        return id;
    }

    private UUID rule(UUID account, UUID category, UUID bank, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO recurring_rules (id, account_id, kind, amount_cents, category_id, bank_account_id,"
                        + " frequency, start_date, next_run_date, created_by, created_at, updated_at)"
                        + " VALUES (?, ?, 'EXPENSE', 100, ?, ?, 'MONTHLY', ?, ?, ?, now(), now())",
                id,
                account,
                category,
                bank,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 1),
                createdBy);
        return id;
    }

    private UUID transaction(UUID account, UUID category, UUID bank, UUID rule, UUID createdBy, LocalDate date) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO transactions (id, account_id, kind, amount_cents, date, category_id, bank_account_id,"
                        + " recurring_rule_id, created_by, created_at, updated_at)"
                        + " VALUES (?, ?, 'EXPENSE', 100, ?, ?, ?, ?, ?, now(), now())",
                id,
                account,
                date,
                category,
                bank,
                rule,
                createdBy);
        return id;
    }

    private int count(String table, UUID accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id = ?", Integer.class, accountId);
    }

    @Test
    void transferShapeIsEnforcedByCheck() {
        UUID acc = account();
        UUID cat = category(acc, "Mercado", "EXPENSE");
        UUID b1 = bank(acc, null);
        UUID b2 = bank(acc, null);
        String sql = "INSERT INTO transactions (id, account_id, kind, amount_cents, date, category_id,"
                + " bank_account_id, destination_bank_account_id, created_at, updated_at)"
                + " VALUES (?, ?, ?, 100, '2026-01-01', ?, ?, ?, now(), now())";

        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), acc, "TRANSFER", cat, b1, b2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), acc, "TRANSFER", null, b1, b1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), acc, "EXPENSE", cat, b1, b2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), acc, "EXPENSE", null, b1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(sql, UUID.randomUUID(), acc, "TRANSFER", null, b1, b2))
                .isEqualTo(1);
    }

    @Test
    void ruleOccurrenceIsUniquePerDate() {
        UUID acc = account();
        UUID cat = category(acc, "Mercado", "EXPENSE");
        UUID bank = bank(acc, null);
        UUID rule = rule(acc, cat, bank, null);
        LocalDate day = LocalDate.of(2026, 1, 1);
        transaction(acc, cat, bank, rule, null, day);

        assertThatThrownBy(() -> transaction(acc, cat, bank, rule, null, day))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_transactions_rule_date");
        // manual transactions (no rule) are not constrained
        transaction(acc, cat, bank, null, null, day);
        transaction(acc, cat, bank, null, null, day);
    }

    @Test
    void accountDelete_cascadesThroughFinanceData() {
        UUID acc = account();
        UUID user = user();
        UUID cat = category(acc, "Mercado", "EXPENSE");
        UUID bank = bank(acc, user);
        UUID rule = rule(acc, cat, bank, user);
        transaction(acc, cat, bank, rule, user, LocalDate.of(2026, 1, 1));
        jdbc.update(
                "INSERT INTO budgets (id, account_id, category_id, monthly_limit_cents, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1000, now(), now())",
                UUID.randomUUID(),
                acc,
                cat);

        assertThat(jdbc.update("DELETE FROM accounts WHERE id = ?", acc)).isEqualTo(1);

        for (String table :
                new String[] {"categories", "bank_accounts", "recurring_rules", "transactions", "budgets"}) {
            assertThat(count(table, acc)).as(table).isZero();
        }
    }

    @Test
    void userDelete_setsCreatedByAndHolderToNull() {
        UUID acc = account();
        UUID user = user();
        UUID cat = category(acc, "Mercado", "EXPENSE");
        UUID bank = bank(acc, user);
        UUID rule = rule(acc, cat, bank, user);
        UUID tx = transaction(acc, cat, bank, null, user, LocalDate.of(2026, 1, 1));

        jdbc.update("DELETE FROM users WHERE id = ?", user);

        assertThat(jdbc.queryForObject("SELECT holder_user_id FROM bank_accounts WHERE id = ?", UUID.class, bank))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT created_by FROM recurring_rules WHERE id = ?", UUID.class, rule))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT created_by FROM transactions WHERE id = ?", UUID.class, tx))
                .isNull();
    }

    @Test
    void ruleDelete_detachesGeneratedTransactions() {
        UUID acc = account();
        UUID cat = category(acc, "Mercado", "EXPENSE");
        UUID bank = bank(acc, null);
        UUID rule = rule(acc, cat, bank, null);
        UUID tx = transaction(acc, cat, bank, rule, null, LocalDate.of(2026, 1, 1));

        jdbc.update("DELETE FROM recurring_rules WHERE id = ?", rule);

        assertThat(jdbc.queryForObject("SELECT recurring_rule_id FROM transactions WHERE id = ?", UUID.class, tx))
                .isNull();
        assertThat(count("transactions", acc)).isEqualTo(1);
    }

    @Test
    void categoryNameUniquenessIgnoresCase() {
        UUID acc = account();
        category(acc, "Mercado", "EXPENSE");

        assertThatThrownBy(() -> category(acc, "mercado", "EXPENSE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_categories_active_name");
        // same name in another kind is allowed
        category(acc, "mercado", "INCOME");
    }
}
