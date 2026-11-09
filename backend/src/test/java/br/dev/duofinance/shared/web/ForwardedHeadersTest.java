package br.dev.duofinance.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.TestcontainersConfiguration;
import br.dev.duofinance.support.TestClockConfiguration;
import br.dev.duofinance.support.TestEmailConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * With {@code server.forward-headers-strategy=native} the work is done by Tomcat's RemoteIpValve, which MockMvc
 * bypasses entirely. So this test starts the embedded server on a random port and uses a real HTTP client.
 * The client connects from loopback, which the valve's default internal-proxies pattern trusts (as nginx on the
 * compose network is trusted in production).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=native")
@Import({
    TestcontainersConfiguration.class,
    TestClockConfiguration.class,
    TestEmailConfiguration.class,
    ForwardedHeadersTest.RequestInfoController.class
})
@ActiveProfiles("test")
class ForwardedHeadersTest {

    @Value("${local.server.port}")
    int port;

    private Map<?, ?> requestInfo(String... headers) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/__test/request-info"));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonMapper.builder().build().readValue(response.body(), Map.class);
    }

    @Test
    void forwardedHeaders_areHonoured() throws Exception {
        Map<?, ?> info = requestInfo("X-Forwarded-Proto", "https", "X-Forwarded-For", "203.0.113.9");

        assertThat(info.get("secure")).isEqualTo(true);
        assertThat(info.get("remoteAddr")).isEqualTo("203.0.113.9");
    }

    @Test
    void withoutForwardedHeaders_requestIsPlainLoopback() throws Exception {
        Map<?, ?> info = requestInfo();

        assertThat(info.get("secure")).isEqualTo(false);
        assertThat((String) info.get("remoteAddr")).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
    }

    @RestController
    static class RequestInfoController {

        @GetMapping("/api/v1/__test/request-info")
        Map<String, Object> info(HttpServletRequest request) {
            return Map.of("secure", request.isSecure(), "remoteAddr", request.getRemoteAddr());
        }
    }
}
