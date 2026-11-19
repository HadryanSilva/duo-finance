package br.dev.duofinance.auth.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
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

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JwtDecoder jwtDecoder;

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
    void ticketShapedLikeAnAccessToken_asBearer_isUnauthenticated() {
        String token = ticketClaimedAsAccessToken();

        assertThat(mvc.get().uri("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatus(401);
    }

    @Test
    void accessTokenDecoder_rejectsTokenWithAudience() {
        String token = ticketClaimedAsAccessToken();

        assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtException.class);
    }

    /** Real user's sub/acc/role plus the ticket audience: only the decoder's aud check can reject it. */
    private String ticketClaimedAsAccessToken() {
        TestSession session = testUsers.registered("dora@mail.com");
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(session.userId().toString())
                .claim("acc", session.accountId().toString())
                .claim("role", "USER")
                .audience(List.of("google-signup"))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
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
