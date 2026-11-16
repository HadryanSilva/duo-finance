package br.dev.duofinance.goal.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.account.service.AccountDeletionService;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Saving the salary category locks the account, then the foreign key checks the category row. Deleting that
 * category takes the account first too: otherwise the delete holds the category row, and a save of that same
 * category waits on it and then fails with a foreign key violation once the delete commits.
 */
@IntegrationTest
class GoalSalaryCategoryRaceTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    AccountDeletionService accountDeletion;

    private boolean anotherBackendWaitsOnLock(int ownPid) {
        Integer waiting = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND pid <> ?",
                Integer.class,
                ownPid);
        return waiting != null && waiting > 0;
    }

    private UUID salaryCategoryId(UUID accountId) {
        return jdbc.queryForObject("SELECT salary_category_id FROM accounts WHERE id = ?", UUID.class, accountId);
    }

    @Test
    void deleteCategory_whileSalarySettingIsSaved_waitsAndClearsIt() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        UUID category = fixtures.category(ana, "Paycheck", CategoryKind.INCOME);

        try (Connection settings = dataSource.getConnection()) {
            settings.setAutoCommit(false);
            int settingsPid;
            try (ResultSet rs = settings.createStatement().executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                settingsPid = rs.getInt(1);
            }
            try (PreparedStatement lock =
                    settings.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, ana.accountId());
                lock.executeQuery().close();
            }

            AtomicReference<Integer> status = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread delete = new Thread(() -> {
                try {
                    status.set(mvc.delete()
                            .uri("/api/v1/categories/" + category)
                            .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(ana))
                            .exchange()
                            .getResponse()
                            .getStatus());
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            delete.start();

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!anotherBackendWaitsOnLock(settingsPid) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(anotherBackendWaitsOnLock(settingsPid))
                    .as("the delete blocks on the account lock held by the settings save")
                    .isTrue();

            // The settings save's foreign key check takes the category row FOR KEY SHARE.
            try (PreparedStatement check =
                    settings.prepareStatement("SELECT id FROM categories WHERE id = ? FOR KEY SHARE NOWAIT")) {
                check.setObject(1, category);
                check.executeQuery().close();
            }
            try (PreparedStatement save =
                    settings.prepareStatement("UPDATE accounts SET salary_category_id = ? WHERE id = ?")) {
                save.setObject(1, category);
                save.setObject(2, ana.accountId());
                save.executeUpdate();
            }
            settings.commit();

            delete.join(10_000);
            assertThat(delete.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(status.get()).isEqualTo(204);
        }
        assertThat(salaryCategoryId(ana.accountId())).isNull();
    }

    @Test
    void deleteAccount_withSalaryCategorySet_removesEverything() {
        TestSession ana = testUsers.verified("ana@mail.com");
        UUID category = fixtures.category(ana, "Paycheck", CategoryKind.INCOME);
        jdbc.update("UPDATE accounts SET salary_category_id = ? WHERE id = ?", category, ana.accountId());

        accountDeletion.delete(ana.accountId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ?", Integer.class, ana.accountId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id = ?", Integer.class, category))
                .isZero();
    }
}
