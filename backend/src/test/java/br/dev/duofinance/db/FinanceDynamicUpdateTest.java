package br.dev.duofinance.db;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.bankaccount.model.BankAccount;
import br.dev.duofinance.bankaccount.repository.BankAccountRepository;
import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.category.repository.CategoryRepository;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import br.dev.duofinance.transaction.model.Transaction;
import br.dev.duofinance.transaction.repository.TransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unlocked PUTs write only the columns they change ({@code @DynamicUpdate}), so a concurrent archive, unarchive or
 * reference change committed between their read and their flush survives.
 */
@IntegrationTest
class FinanceDynamicUpdateTest {

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    CategoryRepository categories;

    @Autowired
    BankAccountRepository bankAccounts;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    private final JsonMapper json = JsonMapper.builder().build();

    /** Runs the load, the concurrent write and the edit in one transaction that commits (and flushes) at the end. */
    private void race(Runnable loadAndEdit) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> loadAndEdit.run());
    }

    private void committedConcurrently(String sql, Object... args) {
        TransactionTemplate concurrent = new TransactionTemplate(txManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        concurrent.executeWithoutResult(c -> jdbc.update(sql, args));
    }

    @Test
    void categoryUpdate_doesNotOverwriteConcurrentArchive() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.category(s, "Cat Teste Dyn", CategoryKind.EXPENSE);

        race(() -> {
            Category loaded = categories.findById(id).orElseThrow();
            committedConcurrently("UPDATE categories SET archived_at = now() WHERE id = ?", id);
            loaded.setName("Supermercado");
            loaded.setUpdatedAt(Instant.now(clock));
        });

        assertThat(jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, id))
                .isEqualTo("Supermercado");
        assertThat(jdbc.queryForObject(
                        "SELECT archived_at IS NOT NULL FROM categories WHERE id = ?", Boolean.class, id))
                .isTrue();
    }

    @Test
    void bankAccountUpdate_doesNotOverwriteConcurrentArchive() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.bankAccount(s, "Main", 0);

        race(() -> {
            BankAccount loaded = bankAccounts.findById(id).orElseThrow();
            committedConcurrently("UPDATE bank_accounts SET archived_at = now() WHERE id = ?", id);
            loaded.setName("Principal");
            loaded.setUpdatedAt(Instant.now(clock));
        });

        assertThat(jdbc.queryForObject("SELECT name FROM bank_accounts WHERE id = ?", String.class, id))
                .isEqualTo("Principal");
        assertThat(jdbc.queryForObject(
                        "SELECT archived_at IS NOT NULL FROM bank_accounts WHERE id = ?", Boolean.class, id))
                .isTrue();
    }

    @Test
    void transactionUpdate_doesNotOverwriteConcurrentRecurringRuleId() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = fixtures.category(s, "Cat Teste Dyn", CategoryKind.EXPENSE);
        UUID tx = fixtures.transaction(
                s, Map.of("kind", "EXPENSE", "amountCents", 500, "date", "2026-11-01", "categoryId", category));
        var rule = mvc.post()
                .uri("/api/v1/recurring-rules")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        700,
                        "categoryId",
                        category,
                        "frequency",
                        "MONTHLY",
                        "startDate",
                        "2099-01-01")))
                .exchange();
        assertThat(rule).hasStatus(HttpStatus.CREATED);
        UUID ruleId = UUID.fromString(json.readTree(rule.getResponse().getContentAsByteArray())
                .path("id")
                .asString());

        race(() -> {
            Transaction loaded = transactions.findById(tx).orElseThrow();
            committedConcurrently("UPDATE transactions SET recurring_rule_id = ? WHERE id = ?", ruleId, tx);
            loaded.assign(
                    loaded.getKind(),
                    999,
                    loaded.getDate(),
                    loaded.getDescription(),
                    loaded.getCategoryId(),
                    loaded.getBankAccountId(),
                    loaded.getDestinationBankAccountId(),
                    Instant.now(clock));
        });

        assertThat(jdbc.queryForObject("SELECT amount_cents FROM transactions WHERE id = ?", Long.class, tx))
                .isEqualTo(999L);
        assertThat(jdbc.queryForObject("SELECT recurring_rule_id FROM transactions WHERE id = ?", UUID.class, tx))
                .isEqualTo(ruleId);
    }
}
