package dev.ledgerflow.common;

import org.springframework.http.HttpStatus;

/** Business-rule failure that maps directly to an HTTP problem response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException accountNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found");
    }

    public static ApiException insufficientFunds() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                "Source account has insufficient funds");
    }

    public static ApiException currencyMismatch() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH",
                "Transfer currency must match both accounts");
    }

    public static ApiException idempotencyKeyReused() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was already used with a different request body");
    }

    public static ApiException sameAccount() {
        return new ApiException(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT",
                "Source and destination accounts must differ");
    }

    public static ApiException badIdempotencyKey() {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_IDEMPOTENCY_KEY",
                "Idempotency-Key header is required (8 to 128 characters)");
    }
}
