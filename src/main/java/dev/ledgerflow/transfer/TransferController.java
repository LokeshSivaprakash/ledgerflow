package dev.ledgerflow.transfer;

import dev.ledgerflow.common.ApiException;
import dev.ledgerflow.transfer.TransferDtos.TransferOutcome;
import dev.ledgerflow.transfer.TransferDtos.TransferRequest;
import dev.ledgerflow.transfer.TransferDtos.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final LedgerService ledger;

    public TransferController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    @Operation(summary = "Move money between two accounts",
            description = "Requires an Idempotency-Key header. Repeating a request with the same key and body "
                    + "returns the original result (200 with Idempotent-Replayed: true) without moving money again.")
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw ApiException.badIdempotencyKey();
        }
        TransferOutcome outcome = ledger.transfer(jwt.getSubject(), idempotencyKey, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.response());
    }
}
