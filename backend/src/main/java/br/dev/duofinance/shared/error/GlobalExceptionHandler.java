package br.dev.duofinance.shared.error;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Renders every failure as an RFC 9457 problem with a stable {@code code}. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://duofinance.dev.br/errors/";

    /** Builds the problem body used by this handler and by security entry points. */
    public static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(TYPE_BASE + code.kebab()));
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        return problem;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ErrorCode code = ex.code();
        String detail = ex.getMessage();
        return ResponseEntity.status(code.status()).body(problem(code, detail));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "Request validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "code", e.getCode() == null ? "Invalid" : e.getCode()))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(ErrorCode.VALIDATION_FAILED, "Malformed request body"));
    }

    /**
     * Every other Spring MVC exception (type mismatch, missing parameter, unknown route, 405, 415, method validation,
     * ...) gets a stable {@code code} derived from its status.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null
                && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            ErrorCode code = codeFor(statusCode);
            problem.setType(URI.create(TYPE_BASE + code.kebab()));
            problem.setProperty("code", code.name());
        }
        return response;
    }

    /** No dedicated codes exist for 405, 415 and similar client errors, so they report VALIDATION_FAILED. */
    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            default -> status.is4xxClientError() ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
        };
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status()).body(problem(ErrorCode.FORBIDDEN, "Access denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(ErrorCode.UNAUTHENTICATED.status())
                .body(problem(ErrorCode.UNAUTHENTICATED, "Authentication required"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(ErrorCode.INTERNAL_ERROR, "Unexpected error"));
    }
}
