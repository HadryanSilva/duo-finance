package br.dev.duofinance.auth.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class SignupTicketTest {

    private static final GoogleIdentity IDENTITY = new GoogleIdentity("sub-123", "ana@mail.com", "Ana");

    @Autowired
    SignupTicketService tickets;

    @Autowired
    TestUsers testUsers;

    @Autowired
    MutableClock clock;

    @Autowired
    MockMvcTester mvc;

    @Test
    void ticket_roundTrips() {
        assertThat(tickets.read(tickets.issue(IDENTITY))).isEqualTo(IDENTITY);
    }

    @Test
    void ticket_withoutName_roundTrips() {
        GoogleIdentity nameless = new GoogleIdentity("sub-9", "bia@mail.com", null);

        assertThat(tickets.read(tickets.issue(nameless))).isEqualTo(nameless);
    }

    @Test
    void ticket_expired_rejected() {
        String ticket = tickets.issue(IDENTITY);
        clock.advance(Duration.ofMinutes(16));

        assertInvalid(ticket);
    }

    @Test
    void ticket_tampered_rejected() {
        String ticket = tickets.issue(IDENTITY);
        int last = ticket.length() - 10; // not the final char: its low bits are base64 padding
        char swapped = ticket.charAt(last) == 'A' ? 'B' : 'A';

        assertInvalid(ticket.substring(0, last) + swapped);
    }

    @Test
    void ticket_asBearer_isUnauthenticated() {
        assertThat(mvc.get().uri("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tickets.issue(IDENTITY)))
                .hasStatus(401);
    }

    @Test
    void accessToken_asTicket_rejected() {
        assertInvalid(testUsers.registered("carla@mail.com").accessToken());
    }

    private void assertInvalid(String ticket) {
        assertThatThrownBy(() -> tickets.read(ticket))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.GOOGLE_SIGNUP_TICKET_INVALID));
    }
}
