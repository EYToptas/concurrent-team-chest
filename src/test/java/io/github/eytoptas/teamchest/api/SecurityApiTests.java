package io.github.eytoptas.teamchest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class SecurityApiTests extends ApiIntegrationTest {

    private Campaign campaign;

    @BeforeEach
    void openCampaign() {
        campaign = activeCampaign();
    }

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/join"),
                Arguments.of(HttpMethod.POST, "/progress"),
                Arguments.of(HttpMethod.POST, "/claim"),
                Arguments.of(HttpMethod.GET, "/get"));
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void everyEndpointRequiresAnAccessToken(HttpMethod method, String path) throws Exception {
        mvc.perform(request(method, path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        postJoin("alice").andExpect(status().isOk());
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        expectRejected("Bearer not-a-jwt");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair attackerKeys = generator.generateKeyPair();
        RSAKey attackerKey = new RSAKey.Builder((RSAPublicKey) attackerKeys.getPublic())
                .privateKey(attackerKeys.getPrivate()).build();
        NimbusJwtEncoder attacker = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(attackerKey)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer()).audience(List.of(jwtProperties.audience())).subject("alice")
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).build();

        String token = attacker.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();

        expectRejected("Bearer " + token);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        expectRejected(bearerWithClaims(claims -> claims.issuer("https://evil.example")));
    }

    @Test
    void tokenForAnotherAudienceIsRejected() throws Exception {
        expectRejected(bearerWithClaims(claims -> claims.audience(List.of("some-other-api"))));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Instant twoHoursAgo = Instant.now().minusSeconds(7200);
        expectRejected(bearerWithClaims(claims -> claims.issuedAt(twoHoursAgo).expiresAt(twoHoursAgo.plusSeconds(3600))));
    }

    @Test
    void tokenWithoutExpiryIsRejected() throws Exception {
        expectRejected(bearerWithClaims(claims -> claims.claims(all -> all.remove("exp"))));
    }

    @Test
    void tokenWithoutSubjectIsRejected() throws Exception {
        expectRejected(bearerWithClaims(claims -> claims.claims(all -> all.remove("sub"))));
    }

    @Test
    void unsignedTokenIsRejected() throws Exception {
        expectRejected("Bearer " + new PlainJWT(nimbusClaims()).serialize());
    }

    @Test
    void hmacSignedTokenIsRejected() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), nimbusClaims());
        token.sign(new MACSigner(secret));

        expectRejected("Bearer " + token.serialize());
    }

    @Test
    void userIdInTheRequestBodyIsIgnored() throws Exception {
        postJoin("alice").andExpect(status().isOk());
        postJoin("bob").andExpect(status().isOk());

        postProgress("alice", newKey(),
                "{\"campaignId\": " + campaign.getId() + ", \"points\": 6, \"userId\": \"bob\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userPoints").value(6));

        assertThat(pointsOf("alice")).isEqualTo(6);
        assertThat(pointsOf("bob")).isZero();
    }

    @Test
    void devTokenEndpointIssuesTokensTheApiAccepts() throws Exception {
        String response = mvc.perform(post("/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": \"dave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(response, "$.accessToken");

        mvc.perform(post("/join").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_member WHERE campaign_id = ? AND user_id = 'dave'",
                Integer.class, campaign.getId())).isEqualTo(1);
    }

    @Test
    void healthCheckIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void metricsOnTheApiPortRequireAnAccessToken() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownPathsRequireAnAccessToken() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isUnauthorized());
    }

    private void expectRejected(String authorization) throws Exception {
        mvc.perform(get("/get").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private JWTClaimsSet nimbusClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(jwtProperties.issuer())
                .audience(jwtProperties.audience())
                .subject("alice")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build();
    }

    private int pointsOf(String userId) {
        return jdbc.queryForObject("SELECT points FROM team_member WHERE campaign_id = ? AND user_id = ?",
                Integer.class, campaign.getId(), userId);
    }
}
