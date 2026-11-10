package br.dev.duofinance.shared.email;

import br.dev.duofinance.shared.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Sends through the Resend HTTP API; any non-2xx response surfaces as a RestClientException. */
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "RESEND")
public class ResendEmailSender implements EmailSender {

    static final String BASE_URL = "https://api.resend.com";

    private final RestClient client;
    private final String from;

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    public ResendEmailSender(RestClient.Builder builder, AppProperties props) {
        this(withTimeouts(builder), props, BASE_URL);
    }

    /** Takes the builder as given (tests bind a mock server to it); production goes through {@link #withTimeouts}. */
    ResendEmailSender(RestClient.Builder builder, AppProperties props, String baseUrl) {
        AppProperties.Email email = props.email();
        this.from = email == null ? null : email.from();
        String apiKey = email == null ? null : email.resendApiKey();
        if (apiKey == null || apiKey.isBlank() || from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "app.email.provider=RESEND requires app.email.resend-api-key and app.email.from to be set");
        }
        this.client = builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    /** A slow or silent Resend must not hang the e-mail dispatch thread: 5 s to connect, 10 s to read. */
    static RestClient.Builder withTimeouts(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return builder.requestFactory(factory);
    }

    @Override
    public void send(EmailMessage message) {
        Map<String, Object> body = Map.of(
                "from", from,
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
