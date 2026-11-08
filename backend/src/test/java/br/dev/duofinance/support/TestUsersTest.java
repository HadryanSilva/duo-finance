package br.dev.duofinance.support;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.TestUsers.TestSession;
import br.dev.duofinance.user.model.UserRole;
import br.dev.duofinance.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class TestUsersTest {

    @Autowired
    TestUsers testUsers;

    @Autowired
    UserRepository users;

    @Autowired
    MockMvcTester mvc;

    @Test
    void verified_marksEmailVerified() {
        TestSession session = testUsers.verified("ana@mail.com");

        assertThat(users.findById(session.userId()).orElseThrow().getEmailVerifiedAt())
                .isNotNull();
    }

    @Test
    void admin_returnsTokenThatReachesAdminRoutes() {
        TestSession session = testUsers.admin("root@mail.com");

        assertThat(users.findById(session.userId()).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);
        // An authenticated ADMIN gets past the role rule (404 = no such route yet, not 401/403).
        assertThat(mvc.get().uri("/api/v1/admin/nothing").header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(session)))
                .hasStatus(404);
    }
}
