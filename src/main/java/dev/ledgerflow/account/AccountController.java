package dev.ledgerflow.account;

import dev.ledgerflow.common.ApiException;
import dev.ledgerflow.transfer.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
@Validated
public class AccountController {

    public record CreateAccountRequest(
            @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code") String currency,
            @PositiveOrZero long initialDepositMinor) {}

    public record AccountResponse(UUID id, String currency, long balanceMinor) {}

    public record EntryResponse(long entryId, UUID transactionId, long deltaMinor, Instant createdAt) {}

    public record EntriesPage(List<EntryResponse> entries, Long nextBefore) {}

    private final LedgerService ledger;
    private final AccountRepository accounts;

    public AccountController(LedgerService ledger, AccountRepository accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAccountRequest req) {
        Account a = ledger.openAccount(jwt.getSubject(), req.currency(), req.initialDepositMinor());
        return toResponse(a);
    }

    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return accounts.findByOwner(jwt.getSubject()).stream().map(AccountController::toResponse).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return toResponse(owned(jwt, id));
    }

    @GetMapping("/{id}/entries")
    public EntriesPage entries(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        owned(jwt, id);
        var rows = accounts.entries(id, before, limit);
        var entries = rows.stream()
                .map(r -> new EntryResponse(r.id(), r.transactionId(), r.deltaMinor(), r.createdAt()))
                .toList();
        Long next = rows.size() == limit ? rows.get(rows.size() - 1).id() : null;
        return new EntriesPage(entries, next);
    }

    private Account owned(Jwt jwt, UUID id) {
        return accounts.find(id)
                .filter(a -> a.ownerSubject().equals(jwt.getSubject()))
                .orElseThrow(ApiException::accountNotFound);
    }

    private static AccountResponse toResponse(Account a) {
        return new AccountResponse(a.id(), a.currency(), a.balanceMinor());
    }
}
