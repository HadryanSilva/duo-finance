package br.dev.duofinance.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

@IntegrationTest
class LoginTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    UserRepository users;

    @Autowired
    MutableClock clock;

    private org.springframework.test.web.servlet.assertj.MvcTestResult login(String email, String password) {
        MockMvcRequestBuilder request = mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
        return request.exchange();
    }

    @Test
    void login_success_returnsSessionAndResetsCounter() {
        var session = testUsers.registered("ana@mail.com");
        login("ana@mail.com", "wrong-password");
        login("ana@mail.com", "wrong-password");
        assertThat(users.findById(session.userId()).orElseThrow().getFailedLoginCount())
                .isEqualTo(2);

        var result = login("ana@mail.com", TestUsers.PASSWORD);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.accessToken").isNotNull();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith("df_refresh=");
        var user = users.findById(session.userId()).orElseThrow();
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void login_wrongPassword_returnsGeneric401() {
        var session = testUsers.registered("ana@mail.com");

        var result = login("ana@mail.com", "wrong-password");

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        assertThat(users.findById(session.userId()).orElseThrow().getFailedLoginCount())
                .isEqualTo(1);
    }

    @Test
    void login_unknownEmail_returnsSameGeneric401() {
        var result = login("nobody@mail.com", "whatever-pass");

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void login_emailIsCaseInsensitive() {
        testUsers.registered("ana@mail.com");

        assertThat(login("ANA@mail.com ", TestUsers.PASSWORD)).hasStatus(HttpStatus.OK);
    }

    @Test
    void login_locksAfterFiveFailuresFor15Minutes() {
        var session = testUsers.registered("ana@mail.com");

        for (int i = 0; i < 5; i++) {
            assertThat(login("ana@mail.com", "wrong-password")).hasStatus(HttpStatus.UNAUTHORIZED);
        }
        var user = users.findById(session.userId()).orElseThrow();
        assertThat(user.getLockedUntil()).isNotNull();
        assertThat(user.getFailedLoginCount()).isZero();

        var locked = login("ana@mail.com", TestUsers.PASSWORD);
        assertThat(locked).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(locked).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(login("ana@mail.com", TestUsers.PASSWORD)).hasStatus(HttpStatus.OK);
        assertThat(users.findById(session.userId()).orElseThrow().getLockedUntil())
                .isNull();
    }
}
