package br.dev.duofinance.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import br.dev.duofinance.IntegrationTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@IntegrationTest
@Import(GlobalExceptionHandlerTest.ErrorsTestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void apiException_rendersProblemDetailWithCode() {
        assertThat(mvc.get().uri("/api/v1/__test/errors/account-full"))
                .hasStatus(409)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.code").isEqualTo("ACCOUNT_FULL");
                    json.assertThat().extractingPath("$.status").isEqualTo(409);
                    json.assertThat()
                            .extractingPath("$.type")
                            .isEqualTo("https://duofinance.dev.br/errors/account-full");
                });
    }

    @Test
    void apiException_usesCustomDetail() {
        assertThat(mvc.get().uri("/api/v1/__test/errors/detailed"))
                .hasStatus(404)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.detail").isEqualTo("Thing 42 missing"));
    }

    @Test
    void beanValidation_rendersFieldErrors() {
        assertThat(mvc.post()
                        .uri("/api/v1/__test/errors/validate")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .hasStatus(400)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
                    json.assertThat().extractingPath("$.errors[0].field").isEqualTo("name");
                    json.assertThat().extractingPath("$.errors[0].code").isEqualTo("NotBlank");
                });
    }

    @Test
    void unexpectedException_returns500WithoutStackTrace() {
        var result = mvc.get().uri("/api/v1/__test/errors/boom");
        assertThat(result)
                .hasStatus(500)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("INTERNAL_ERROR"));
        assertThat(result).bodyText().doesNotContain("Exception").doesNotContain("secret-internal-message");
    }

    @Test
    void accessDenied_returns403Forbidden() {
        assertThat(mvc.get().uri("/api/v1/__test/errors/denied"))
                .hasStatus(403)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("FORBIDDEN"));
    }

    @Test
    void authenticationException_returns401Unauthenticated() {
        assertThat(mvc.get().uri("/api/v1/__test/errors/unauth"))
                .hasStatus(401)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void malformedJson_returns400ValidationFailed() {
        assertThat(mvc.post()
                        .uri("/api/v1/__test/errors/validate")
                        .contentType(APPLICATION_JSON)
                        .content("{"))
                .hasStatus(400)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void springHandledExceptions_carryCodeDerivedFromStatus() {
        assertThat(mvc.get().uri("/api/v1/__test/errors/no-such-route"))
                .hasStatus(404)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("NOT_FOUND"));
        assertThat(mvc.delete().uri("/api/v1/__test/errors/account-full"))
                .hasStatus(405)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("VALIDATION_FAILED"));
        assertThat(mvc.post()
                        .uri("/api/v1/__test/errors/validate")
                        .contentType("text/plain")
                        .content("x"))
                .hasStatus(415)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("VALIDATION_FAILED"));
        assertThat(mvc.get().uri("/api/v1/__test/errors/required-param"))
                .hasStatus(400)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("VALIDATION_FAILED"));
    }

    record Payload(@NotBlank String name) {}

    @RestController
    @RequestMapping("/api/v1/__test/errors")
    static class ErrorsTestController {

        @GetMapping("/account-full")
        void accountFull() {
            throw new ApiException(ErrorCode.ACCOUNT_FULL);
        }

        @GetMapping("/detailed")
        void detailed() {
            throw new ApiException(ErrorCode.NOT_FOUND, "Thing 42 missing");
        }

        @GetMapping("/denied")
        void denied() {
            throw new org.springframework.security.access.AccessDeniedException("nope");
        }

        @GetMapping("/unauth")
        void unauth() {
            throw new org.springframework.security.authentication.BadCredentialsException("nope");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret-internal-message");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Payload payload) {}

        @GetMapping("/required-param")
        void requiredParam(@RequestParam String q) {}
    }
}
