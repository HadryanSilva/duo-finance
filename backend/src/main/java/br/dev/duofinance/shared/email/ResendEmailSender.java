package br.dev.duofinance.shared.email;

import br.dev.duofinance.shared.config.AppProperties;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Sends through the Resend HTTP API; any non-2xx response surfaces as a RestClientException. */
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "RESEND")
public class ResendEmailSender implements EmailSender {

    static final String BASE_URL = "https://api.resend.com";

    private final RestClient client;
    private final String from;

    public ResendEmailSender(RestClient.Builder builder, AppProperties props) {
        AppProperties.Email email = props.email();
        this.from = email == null ? null : email.from();
        String apiKey = email == null ? null : email.resendApiKey();
        this.client = builder.baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    @Override
    public void send(EmailMessage message) {
        Map<String, Object> body = Map.of(
                "from", from == null ? "" : from,
                "to", List.of(message.to()),
                "subject", message.subject(),
                "html", message.html());
        client.post()
                .uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
