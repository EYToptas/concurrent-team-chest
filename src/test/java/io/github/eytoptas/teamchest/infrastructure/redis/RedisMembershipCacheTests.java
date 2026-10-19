package io.github.eytoptas.teamchest.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.eytoptas.teamchest.application.cache.MembershipCache;
import io.github.eytoptas.teamchest.persistence.PersistenceIntegrationTest;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisMembershipCacheTests extends PersistenceIntegrationTest {

    @Autowired
    private MembershipCache membershipCache;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private CacheProperties cacheProperties;
    @Autowired
    private Clock clock;

    private final long campaignId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final String userId = "user-" + UUID.randomUUID();

    @Test
    void aRememberedMembershipIsFoundForItsCampaignAndUserOnly() {
        membershipCache.remember(campaignId, userId, 42);

        assertThat(membershipCache.findTeamId(campaignId, userId)).contains(42L);
        assertThat(membershipCache.findTeamId(campaignId + 1, userId)).as("other campaign").isEmpty();
        assertThat(membershipCache.findTeamId(campaignId, userId + "x")).as("other user").isEmpty();
    }

    @Test
    void userIdsWithSeparatorsCannotCollide() {
        membershipCache.remember(campaignId, "a:b", 1);

        assertThat(membershipCache.findTeamId(campaignId, "a")).isEmpty();
        assertThat(membershipCache.findTeamId(campaignId, "a:b")).contains(1L);
    }

    @Test
    void membershipsExpireAfterTheMembershipTtl() {
        membershipCache.remember(campaignId, userId, 42);

        assertThat(redis.getExpire(RedisKeys.membership(campaignId, userId), TimeUnit.MILLISECONDS))
                .isBetween(1L, cacheProperties.membershipTtl().toMillis());
    }

    @Test
    void unreachableRedisIsAMissAndNeverAnError() {
        try (UnreachableRedis unreachable = new UnreachableRedis(clock)) {
            RedisMembershipCache unreachableCache =
                    new RedisMembershipCache(unreachable.template, cacheProperties, unreachable.circuitBreaker);

            assertThatCode(() -> {
                assertThat(unreachableCache.findTeamId(campaignId, userId)).isEmpty();
                unreachableCache.remember(campaignId, userId, 42);
            }).doesNotThrowAnyException();
        }
    }
}
