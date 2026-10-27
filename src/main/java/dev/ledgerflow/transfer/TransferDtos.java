package dev.ledgerflow.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {

    private TransferDtos() {}

    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @Positive long amountMinor,
            @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code") String currency) {}

    public record TransferResponse(
            UUID transactionId,
            UUID fromAccountId,
            UUID toAccountId,
            long amountMinor,
            String currency,
            long fromBalanceMinor,
            Instant createdAt) {}

    /** {@code replayed} is true when the response came from an earlier request with the same Idempotency-Key. */
    public record TransferOutcome(TransferResponse response, boolean replayed) {}
}
