package dev.ledgerflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue("false") boolean devTokenEndpoint) {

    public record Jwt(
            @DefaultValue("local-dev-only-secret-change-me-0123456789") String secret,
            @DefaultValue("PT1H") Duration ttl) {}
}
