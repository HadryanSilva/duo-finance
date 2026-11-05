package br.dev.duofinance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class HealthEndpointTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void health_isUpAndPublic() {
        assertThat(mvc.get().uri("/api/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }
}
