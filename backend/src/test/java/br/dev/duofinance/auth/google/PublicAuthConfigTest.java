package br.dev.duofinance.auth.google;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class PublicAuthConfigTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void registrationMode_exposesGoogleClientId() {
        var result = mvc.get().uri("/api/v1/public/registration-mode").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.googleClientId").isEqualTo("test-client-id");
    }
}
