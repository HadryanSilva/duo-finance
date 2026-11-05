package br.dev.duofinance.shared.email;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import br.dev.duofinance.shared.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResendEmailSenderTest {

    private MockRestServiceServer server;
    private ResendEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new AppProperties(
                null,
                null,
                null,
                new AppProperties.Email("Duo <no-reply@duo.dev>", AppProperties.EmailProvider.RESEND, "re_test"));
        sender = new ResendEmailSender(builder, props);
    }

    @Test
    void resend_postsExpectedPayload() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(header("Authorization", "Bearer re_test"))
                .andExpect(jsonPath("$.from").value("Duo <no-reply@duo.dev>"))
                .andExpect(jsonPath("$.to[0]").value("ana@mail.com"))
                .andExpect(jsonPath("$.subject").value("Olá"))
                .andExpect(jsonPath("$.html").value("<p>x</p>"))
                .andRespond(withSuccess("{\"id\":\"1\"}", APPLICATION_JSON));

        sender.send(new EmailMessage("ana@mail.com", "Olá", "<p>x</p>"));

        server.verify();
    }

    @Test
    void resend_non2xx_throws() {
        server.expect(requestTo("https://api.resend.com/emails")).andRespond(withServerError());

        assertThatThrownBy(() -> sender.send(new EmailMessage("ana@mail.com", "s", "h")))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
    }
}
