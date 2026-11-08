package br.dev.duofinance.shared.error;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes. The frontend translates them; never rename an existing one. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    SESSION_STALE(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    REFRESH_RACE(HttpStatus.UNAUTHORIZED),
    SUBSCRIPTION_INACTIVE(HttpStatus.PAYMENT_REQUIRED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),
    ACCESS_CODE_INVALID(HttpStatus.BAD_REQUEST),
    INVITE_INVALID(HttpStatus.BAD_REQUEST),
    INVITE_EXPIRED(HttpStatus.GONE),
    INVITE_SELF(HttpStatus.CONFLICT),
    INVITE_PENDING_EXISTS(HttpStatus.CONFLICT),
    ACCOUNT_FULL(HttpStatus.CONFLICT),
    ALREADY_IN_COUPLE(HttpStatus.CONFLICT),
    ACCOUNT_HAS_DATA(HttpStatus.CONFLICT),
    NOT_IN_COUPLE(HttpStatus.CONFLICT),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN),
    TOKEN_INVALID(HttpStatus.BAD_REQUEST),
    BANK_ACCOUNT_ARCHIVED(HttpStatus.CONFLICT),
    BANK_ACCOUNT_IN_USE(HttpStatus.CONFLICT),
    CATEGORY_ARCHIVED(HttpStatus.CONFLICT),
    CATEGORY_KIND_MISMATCH(HttpStatus.BAD_REQUEST),
    CATEGORY_NAME_TAKEN(HttpStatus.CONFLICT),
    CATEGORY_IN_USE(HttpStatus.CONFLICT),
    TRANSFER_INVALID(HttpStatus.BAD_REQUEST),
    RECURRING_DATE_TAKEN(HttpStatus.CONFLICT),
    IN_USE_BY_ACTIVE_RULE(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Lowercase, hyphenated form used in the problem {@code type} URI. */
    public String kebab() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
