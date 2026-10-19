package io.github.eytoptas.teamchest.infrastructure.security.dev;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
public class DevTokenController {

    private final DevTokenIssuer issuer;

    public DevTokenController(DevTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @PostMapping("/dev/tokens")
    public DevTokenResponse issue(@Valid @RequestBody DevTokenRequest request) {
        DevTokenIssuer.IssuedToken token = issuer.issue(request.userId());
        return new DevTokenResponse(token.accessToken(), "Bearer", token.expiresAt());
    }

    public record DevTokenRequest(@NotBlank @Size(max = 64) String userId) {
    }

    public record DevTokenResponse(String accessToken, String tokenType, Instant expiresAt) {
    }
}
