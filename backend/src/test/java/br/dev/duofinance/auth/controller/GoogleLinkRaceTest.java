package br.dev.duofinance.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.TestGoogleTokens;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.user.model.IdentityProvider;
import br.dev.duofinance.user.repository.UserIdentityRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** A concurrent request already linked the same Google account: the lookup by subject missed it, link() sees it. */
@IntegrationTest
class GoogleLinkRaceTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    TestGoogleTokens tokens;

    @MockitoSpyBean
    UserIdentityRepository identities;

    private MvcTestResult google(String idToken) {
        return mvc.post()
                .uri("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"%s\"}".formatted(idToken))
                .exchange();
    }

    @Test
    void google_sameSubjectAlreadyLinkedWhenLinking_authenticates() {
        testUsers.verified("ana@mail.com");
        assertThat(google(tokens.idToken("g-1", "ana@mail.com", true))).hasStatus(HttpStatus.OK);
        doReturn(Optional.empty())
                .when(identities)
                .findByProviderAndProviderSubject(eq(IdentityProvider.GOOGLE), eq("g-1"));

        var result = google(tokens.idToken("g-1", "ana@mail.com", true));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED");
    }
}
