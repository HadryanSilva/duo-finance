package br.dev.duofinance.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.access.service.RegistrationGate;
import br.dev.duofinance.account.model.Invitation;
import br.dev.duofinance.account.model.InvitationStatus;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.auth.dto.RegisterRequest;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The inviter cancels the invitation after the gate approved it but before the sign-up transaction joins. */
@IntegrationTest
class RegisterInvitationRaceTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    InvitationRepository invitations;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    MutableClock clock;

    @MockitoSpyBean
    RegistrationGate gate;

    @Test
    void register_invitationCanceledAfterGateCheck_returns400AndDoesNotJoin() {
        TestSession ana = testUsers.verified("ana@mail.com");
        String raw = SecureTokens.generate();
        Instant now = Instant.now(clock);
        Invitation invitation = invitations.saveAndFlush(new Invitation(
                ana.accountId(),
                "rui@mail.com",
                SecureTokens.sha256Hex(raw),
                ana.userId(),
                now.plus(Duration.ofDays(7)),
                now));
        TransactionTemplate concurrent = new TransactionTemplate(txManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(call -> {
                    Object grant = call.callRealMethod();
                    concurrent.executeWithoutResult(s ->
                            jdbc.update("UPDATE invitations SET status = 'CANCELED' WHERE id = ?", invitation.getId()));
                    return grant;
                })
                .when(gate)
                .authorize(any(RegisterRequest.class), anyString());

        var result = mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"rui@mail.com","password":"s3cret-pass","displayName":"Rui","locale":"en",
                         "acceptTerms":true,"termsVersion":"2026-11","invitationToken":"%s"}
                        """.formatted(raw))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVITE_INVALID");
        assertThat(invitations.findById(invitation.getId()).orElseThrow().getStatus())
                .isEqualTo(InvitationStatus.CANCELED);
        assertThat(users.findByEmail("rui@mail.com")).isEmpty();
    }
}
