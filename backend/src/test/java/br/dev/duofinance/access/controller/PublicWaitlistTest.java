package br.dev.duofinance.access.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.access.model.WaitlistStatus;
import br.dev.duofinance.access.repository.WaitlistEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class PublicWaitlistTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WaitlistEntryRepository entries;

    private MvcTestResult join(String body) {
        return mvc.post()
                .uri("/api/v1/public/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    @Test
    void registrationMode_isPublic() {
        var result = mvc.get().uri("/api/v1/public/registration-mode").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.mode").isEqualTo("INVITE_ONLY");
    }

    @Test
    void joinWaitlist_isIdempotentAndCaseInsensitive() {
        var first = join("{\"email\":\"Ana@x.com\",\"locale\":\"pt-BR\"}");
        var second = join("{\"email\":\" ana@x.com \",\"locale\":\"en\"}");

        assertThat(first).hasStatus(HttpStatus.ACCEPTED);
        assertThat(second).hasStatus(HttpStatus.ACCEPTED);
        assertThat(entries.findAll()).hasSize(1).first().satisfies(e -> {
            assertThat(e.getEmail()).isEqualTo("ana@x.com");
            assertThat(e.getLocale()).isEqualTo("pt-BR");
            assertThat(e.getStatus()).isEqualTo(WaitlistStatus.PENDING);
        });
    }

    @Test
    void joinWaitlist_rejectsInvalidInput() {
        assertThat(join("{\"email\":\"not-an-email\",\"locale\":\"pt-BR\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(join("{\"email\":\"a@x.com\",\"locale\":\"fr\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(entries.count()).isZero();
    }
}
