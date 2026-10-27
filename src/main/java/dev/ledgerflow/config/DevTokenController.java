package dev.ledgerflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Issues a token for any subject. Registered only when ledger.dev-token-endpoint=true (local demo use). */
@RestController
@ConditionalOnProperty(prefix = "ledger", name = "dev-token-endpoint", havingValue = "true")
public class DevTokenController {

    public record TokenRequest(@NotBlank @Size(max = 100) String subject) {}

    private final JwtEncoder encoder;
    private final LedgerProperties props;

    public DevTokenController(JwtEncoder encoder, LedgerProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    @PostMapping("/api/dev/token")
    public Map<String, String> token(@Valid @RequestBody TokenRequest req) {
        return Map.of("token", issue(encoder, props, req.subject()));
    }

    public static String issue(JwtEncoder encoder, LedgerProperties props, String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(props.jwt().ttl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
