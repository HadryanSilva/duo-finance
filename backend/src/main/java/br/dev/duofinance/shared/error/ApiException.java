package br.dev.duofinance.shared.error;

import java.util.List;
import java.util.Map;

public class ApiException extends RuntimeException {

    /** A field-level failure rendered in the problem's {@code errors[]}, like Bean Validation failures. */
    public record FieldError(String field, String code) {}

    private final ErrorCode code;
    private final List<FieldError> fieldErrors;
    private final Map<String, Object> properties;

    public ApiException(ErrorCode code) {
        this(code, null);
    }

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of(), Map.of());
    }

    private ApiException(ErrorCode code, String detail, List<FieldError> fieldErrors, Map<String, Object> properties) {
        super(detail != null ? detail : code.name());
        this.code = code;
        this.fieldErrors = fieldErrors;
        this.properties = properties;
    }

    /** VALIDATION_FAILED carrying one field error. */
    public static ApiException invalidField(String field, String fieldCode) {
        return invalidFields(List.of(new FieldError(field, fieldCode)));
    }

    /** VALIDATION_FAILED carrying every given field error. */
    public static ApiException invalidFields(List<FieldError> errors) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED, "Request validation failed", List.copyOf(errors), Map.of());
    }

    /**
     * IMPORT_ROW_INVALID for the statement record starting on the given 1-based physical line (header included); the
     * problem carries it as {@code line} so the client can point the user at it.
     */
    public static ApiException importRowInvalid(int line) {
        return new ApiException(ErrorCode.IMPORT_ROW_INVALID, "line " + line, List.of(), Map.of("line", line));
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    /** Extra top-level members of the problem body; empty for most errors. */
    public Map<String, Object> properties() {
        return properties;
    }
}
