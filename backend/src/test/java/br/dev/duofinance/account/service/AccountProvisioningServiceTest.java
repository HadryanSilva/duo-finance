package br.dev.duofinance.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.account.model.Account;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class AccountProvisioningServiceTest {

    @Autowired
    AccountProvisioningService provisioning;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Test
    void soloAccount_hasMemberBetaSubscriptionAndLocalizedCategories() {
        User user = users.saveAndFlush(new User("ana@mail.com", "Ana", "pt-BR", "2026-11", Instant.now(clock)));

        Account account = provisioning.createSoloAccount(user);

        assertThat(account.getName()).isEqualTo("Finanças de Ana");
        assertThat(account.getCurrency()).isEqualTo("EUR");
        assertThat(jdbc.queryForList("SELECT user_id FROM account_members WHERE account_id = ?", account.getId()))
                .hasSize(1)
                .first()
                .satisfies(row -> assertThat(row.get("user_id")).isEqualTo(user.getId()));
        Map<String, Object> sub = jdbc.queryForMap(
                "SELECT plan, status, provider FROM subscriptions WHERE account_id = ?", account.getId());
        assertThat(sub)
                .containsEntry("plan", "BETA")
                .containsEntry("status", "ACTIVE")
                .containsEntry("provider", "NONE");
        List<String> names = jdbc.queryForList(
                "SELECT name FROM categories WHERE account_id = ? AND system_default = true",
                String.class,
                account.getId());
        assertThat(names).hasSize(13).contains("Mercado", "Salário");
    }

    @Test
    void soloAccount_inEnglish_usesEnglishNamesAndAccountName() {
        User user = users.saveAndFlush(new User("bob@mail.com", "Bob", "en", "2026-11", Instant.now(clock)));

        Account account = provisioning.createSoloAccount(user);

        assertThat(account.getName()).isEqualTo("Bob's finances");
        assertThat(jdbc.queryForList("SELECT name FROM categories WHERE account_id = ?", String.class, account.getId()))
                .hasSize(13)
                .contains("Groceries", "Salary");
    }
}
