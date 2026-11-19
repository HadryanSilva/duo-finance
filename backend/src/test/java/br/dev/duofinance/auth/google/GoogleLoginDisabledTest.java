package br.dev.duofinance.auth.google;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Without a Google client id the feature is off: its endpoints answer 404 before any body validation. */
@IntegrationTest
@TestPropertySource(properties = "app.auth.google-client-id=")
class GoogleLoginDisabledTest {

    @Autowired
    MockMvcTester mvc;

    private MvcTestResult post(String uri, String body) {
        return mvc.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static void assertDisabled(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("GOOGLE_LOGIN_DISABLED");
    }

    @Test
    void google_disabled_is404EvenForValidOrMalformedBodies() {
        assertDisabled(post("/api/v1/auth/google", "{\"credential\":\"some-token\"}"));
        assertDisabled(post("/api/v1/auth/google", "{}"));
        assertDisabled(post("/api/v1/auth/google", "not json"));
    }

    @Test
    void googleComplete_disabled_is404EvenForValidOrMalformedBodies() {
        assertDisabled(post("/api/v1/auth/google/complete", """
                {"signupTicket":"t","displayName":"Ana","locale":"pt-BR","acceptTerms":true,"termsVersion":"2026-11"}
                """));
        assertDisabled(post("/api/v1/auth/google/complete", "{}"));
        assertDisabled(post("/api/v1/auth/google/complete", "not json"));
    }

    @Test
    void registrationMode_disabled_hasNoGoogleClientId() {
        var result = mvc.get().uri("/api/v1/public/registration-mode").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.googleClientId").isNull();
    }
}
