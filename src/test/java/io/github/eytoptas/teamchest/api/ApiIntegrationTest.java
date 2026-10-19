package io.github.eytoptas.teamchest.api;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.eytoptas.teamchest.application.ServiceIntegrationTest;
import io.github.eytoptas.teamchest.infrastructure.security.JwtProperties;
import io.github.eytoptas.teamchest.infrastructure.security.dev.DevTokenIssuer;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

public abstract class ApiIntegrationTest extends ServiceIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DevTokenIssuer devTokenIssuer;
    @Autowired
    private JwtEncoder trustedEncoder;
    @Autowired
    protected JwtProperties jwtProperties;

    protected MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    protected String bearer(String userId) {
        return "Bearer " + devTokenIssuer.issue(userId).accessToken();
    }

    protected String bearerWithClaims(Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .subject("alice")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600));
        customizer.accept(claims);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return "Bearer " + trustedEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    protected ResultActions postJoin(String userId) throws Exception {
        return mvc.perform(post("/join").header(HttpHeaders.AUTHORIZATION, bearer(userId)));
    }

    protected ResultActions postProgress(String userId, String idempotencyKey, String jsonBody) throws Exception {
        return mvc.perform(post("/progress")
                .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody));
    }

    protected ResultActions postProgress(String userId, long campaignId, int points) throws Exception {
        return postProgress(userId, newKey(), progressBody(campaignId, points));
    }

    protected ResultActions postClaim(String userId, String idempotencyKey, long campaignId) throws Exception {
        return mvc.perform(post("/claim")
                .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"campaignId\": " + campaignId + "}"));
    }

    protected ResultActions getOverview(String userId) throws Exception {
        return mvc.perform(get("/get").header(HttpHeaders.AUTHORIZATION, bearer(userId)));
    }

    protected static String progressBody(long campaignId, int points) {
        return "{\"campaignId\": " + campaignId + ", \"points\": " + points + "}";
    }
}
