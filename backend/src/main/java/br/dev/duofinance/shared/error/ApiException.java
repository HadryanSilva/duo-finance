package br.dev.duofinance.shared.error;

public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        this(code, null);
    }

    public ApiException(ErrorCode code, String detail) {
        super(detail != null ? detail : code.name());
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
