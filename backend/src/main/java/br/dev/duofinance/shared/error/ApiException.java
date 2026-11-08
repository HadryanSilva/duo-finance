package br.dev.duofinance.shared.error;

import java.util.List;

public class ApiException extends RuntimeException {

    /** A field-level failure rendered in the problem's {@code errors[]}, like Bean Validation failures. */
    public record FieldError(String field, String code) {}

    private final ErrorCode code;
    private final List<FieldError> fieldErrors;

    public ApiException(ErrorCode code) {
        this(code, null);
    }

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of());
    }

    private ApiException(ErrorCode code, String detail, List<FieldError> fieldErrors) {
        super(detail != null ? detail : code.name());
        this.code = code;
        this.fieldErrors = fieldErrors;
    }

    /** VALIDATION_FAILED carrying one field error. */
    public static ApiException invalidField(String field, String fieldCode) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED, "Request validation failed", List.of(new FieldError(field, fieldCode)));
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
