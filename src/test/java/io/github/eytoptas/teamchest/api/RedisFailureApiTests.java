package io.github.eytoptas.teamchest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.eytoptas.teamchest.TestcontainersConfiguration;
import io.github.eytoptas.teamchest.application.cache.TeamStandingCache;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.infrastructure.redis.RedisCircuitBreaker;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;

@TestPropertySource(properties = {
        "spring.data.redis.timeout=250ms",
        "team-chest.redis.failure-cooldown=300ms",
        "team-chest.cache.team-ttl=3s",
        "team-chest.rate-limit.enabled=true",
        "team-chest.rate-limit.progress.capacity=1",
        "team-chest.rate-limit.progress.refill-per-second=0.001"})
class RedisFailureApiTests extends ApiIntegrationTest {

    private static final Duration TEAM_TTL = Duration.ofSeconds(3);

    @Autowired
    private RedisCircuitBreaker circuitBreaker;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private TeamStandingCache teamStandingCache;

    private Campaign campaign;
    private String user;

    @BeforeEach
    void redisIsReachable() {
        awaitRedisReachable();
        campaign = activeCampaign();
        user = "outage-" + UUID.randomUUID();
    }

    @Test
    void whileRedisHangsEveryEndpointWorksFromMySql() throws Exception {
        postJoin(user).andExpect(status().isOk());
        getOverview(user).andExpect(jsonPath("$.me.points").value(0));

        pauseRedis();
        try {
            postProgress(user, campaign.getId(), 300)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.teamCompleted").value(true));
            postClaim(user, newKey(), campaign.getId()).andExpect(status().isOk());
            getOverview(user)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.me.points").value(300))
                    .andExpect(jsonPath("$.me.claimed").value(true))
                    .andExpect(jsonPath("$.canClaim").value(false));
        } finally {
            resumeRedis();
        }
        assertThat(circuitBreaker.isOpen()).as("the outage was noticed").isTrue();
    }

    @Test
    void whileRedisHangsRateLimitsFailOpen() throws Exception {
        postJoin(user).andExpect(status().isOk());
        postProgress(user, campaign.getId(), 1).andExpect(status().isOk());
        postProgress(user, campaign.getId(), 1).andExpect(status().isTooManyRequests());

        pauseRedis();
        try {
            postProgress(user, campaign.getId(), 1).andExpect(status().isOk());
        } finally {
            resumeRedis();
        }
    }

    @Test
    void afterRedisRecoversGetConvergesWithinTheTtl() throws Exception {
        postJoin(user).andExpect(status().isOk());
        getOverview(user).andExpect(jsonPath("$.me.points").value(0));
        long teamId = members.findTeamIdByCampaignIdAndUserId(campaign.getId(), user).orElseThrow();

        pauseRedis();
        try {
            postProgress(user, campaign.getId(), 9).andExpect(status().isOk());
        } finally {
            resumeRedis();
        }
        awaitRedisReachable();

        Awaitility.await().atMost(TEAM_TTL.plusSeconds(3)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> getOverview(user).andExpect(jsonPath("$.me.points").value(9)));
        long committedVersion = jdbc.queryForObject("SELECT state_seq FROM team WHERE id = ?", Long.class, teamId);
        assertThat(teamStandingCache.find(campaign.getId(), teamId))
                .hasValueSatisfying(cached -> assertThat(cached.version()).isEqualTo(committedVersion));
    }

    @Test
    void withoutItsInvalidationAChangeIsVisibleAtTheLatestAfterTheTtl() throws Exception {
        postJoin(user).andExpect(status().isOk());
        getOverview(user).andExpect(jsonPath("$.me.points").value(0));
        long teamId = members.findTeamIdByCampaignIdAndUserId(campaign.getId(), user).orElseThrow();

        applyPoints(teams.findById(teamId).orElseThrow(), user, 4);

        getOverview(user).andExpect(jsonPath("$.me.points").value(0));
        Awaitility.await().atMost(TEAM_TTL.plusSeconds(3)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> getOverview(user).andExpect(jsonPath("$.me.points").value(4)));
    }

    private static void pauseRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(redisContainerId()).exec();
    }

    private static void resumeRedis() {
        DockerClientFactory.instance().client().unpauseContainerCmd(redisContainerId()).exec();
    }

    private static String redisContainerId() {
        return TestcontainersConfiguration.redis().getContainerId();
    }

    private void awaitRedisReachable() {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                .until(() -> circuitBreaker.execute("test probe", () -> {
                    redis.hasKey("tc:v1:probe");
                    return true;
                }, false) && !circuitBreaker.isOpen());
    }
}
