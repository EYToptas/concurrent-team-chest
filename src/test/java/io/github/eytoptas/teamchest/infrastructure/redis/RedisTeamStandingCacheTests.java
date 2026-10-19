package io.github.eytoptas.teamchest.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.eytoptas.teamchest.application.cache.TeamStandingCache;
import io.github.eytoptas.teamchest.application.query.TeamOverview.MemberStanding;
import io.github.eytoptas.teamchest.application.query.TeamOverview.TeamStanding;
import io.github.eytoptas.teamchest.persistence.PersistenceIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class RedisTeamStandingCacheTests extends PersistenceIntegrationTest {

    @Autowired
    private TeamStandingCache cache;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private CacheProperties cacheProperties;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private Clock clock;

    private final long campaignId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final long teamId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    @Test
    void missWhenNothingIsCached() {
        assertThat(cache.find(campaignId, teamId)).isEmpty();
    }

    @Test
    void offeredStandingIsServedWithItsVersion() {
        cache.offer(campaignId, standing(teamId, 3));

        assertThat(cache.find(campaignId, teamId)).contains(standing(teamId, 3));
    }

    @Test
    void entriesAndTombstonesExpireAfterTheTeamTtl() {
        cache.offer(campaignId, standing(teamId, 3));
        assertThat(ttlMillis()).isBetween(1L, cacheProperties.teamTtl().toMillis());

        cache.invalidate(campaignId, teamId, 4);
        assertThat(redis.opsForHash().entries(key())).containsOnlyKeys("v");
        assertThat(ttlMillis()).isBetween(1L, cacheProperties.teamTtl().toMillis());
    }

    @Test
    void aSlowReaderCannotPutBackAnInvalidatedSnapshot() {
        cache.offer(campaignId, standing(teamId, 5));

        cache.invalidate(campaignId, teamId, 6);
        assertThat(cache.find(campaignId, teamId)).isEmpty();

        cache.offer(campaignId, standing(teamId, 5));
        assertThat(cache.find(campaignId, teamId)).as("older snapshot rejected").isEmpty();

        cache.offer(campaignId, standing(teamId, 6));
        assertThat(cache.find(campaignId, teamId)).contains(standing(teamId, 6));
    }

    @Test
    void aLateInvalidationNeverMovesTheVersionBackwards() {
        cache.invalidate(campaignId, teamId, 7);
        cache.invalidate(campaignId, teamId, 6);

        cache.offer(campaignId, standing(teamId, 6));
        assertThat(cache.find(campaignId, teamId)).isEmpty();
        cache.offer(campaignId, standing(teamId, 7));
        assertThat(cache.find(campaignId, teamId)).contains(standing(teamId, 7));

        cache.offer(campaignId, standing(teamId, 9));
        cache.invalidate(campaignId, teamId, 8);
        assertThat(cache.find(campaignId, teamId)).as("an old notification keeps newer data").contains(standing(teamId, 9));
    }

    @Test
    void anOlderSnapshotNeverReplacesANewerOne() {
        cache.offer(campaignId, standing(teamId, 8));
        cache.offer(campaignId, standing(teamId, 7));

        assertThat(cache.find(campaignId, teamId)).contains(standing(teamId, 8));
    }

    @Test
    void concurrentFillsKeepTheNewestVersion() throws Exception {
        List<Runnable> fills = new ArrayList<>();
        LongStream.rangeClosed(1, 32).forEach(version -> fills.add(() -> cache.offer(campaignId, standing(teamId, version))));
        Collections.shuffle(fills);

        runConcurrently(fills);

        assertThat(cache.find(campaignId, teamId)).contains(standing(teamId, 32));
    }

    @Test
    void concurrentFillsAndInvalidationsConvergeToTheNewestData() throws Exception {
        List<Runnable> operations = new ArrayList<>();
        for (long version = 1; version <= 20; version++) {
            long v = version;
            operations.add(() -> cache.offer(campaignId, standing(teamId, v)));
            operations.add(() -> cache.invalidate(campaignId, teamId, v));
        }
        Collections.shuffle(operations);

        runConcurrently(operations);

        assertThat(cache.find(campaignId, teamId)).contains(standing(teamId, 20));
    }

    @Test
    void keysAreScopedByCampaignAndTeam() {
        long otherTeamId = teamId + 1;
        cache.offer(campaignId, standing(teamId, 3));

        assertThat(cache.find(campaignId + 1, teamId)).as("same team id, other campaign").isEmpty();
        assertThat(cache.find(campaignId, otherTeamId)).isEmpty();
        cache.invalidate(campaignId, otherTeamId, 100);
        cache.invalidate(campaignId + 1, teamId, 100);
        assertThat(cache.find(campaignId, teamId)).contains(standing(teamId, 3));
    }

    @Test
    void anEntryThatCannotBeTrustedIsAMiss() {
        redis.opsForHash().putAll(key(), Map.of("v", "1", "d", "not json"));
        assertThat(cache.find(campaignId, teamId)).isEmpty();

        redis.opsForHash().putAll(key(), Map.of("v", "1", "d", jsonMapper.writeValueAsString(standing(teamId + 1, 1))));
        assertThat(cache.find(campaignId, teamId)).as("payload of another team").isEmpty();

        redis.opsForHash().putAll(key(), Map.of("v", "2", "d", jsonMapper.writeValueAsString(standing(teamId, 1))));
        assertThat(cache.find(campaignId, teamId)).as("payload of another version").isEmpty();
    }

    @Test
    void aDisabledCacheIsBypassedButStillInvalidated() {
        CacheProperties disabled = new CacheProperties(false, cacheProperties.teamTtl(), cacheProperties.membershipTtl());
        RedisTeamStandingCache disabledCache = new RedisTeamStandingCache(redis, jsonMapper, disabled,
                new RedisCircuitBreaker(clock, Duration.ofSeconds(1)));

        disabledCache.offer(campaignId, standing(teamId, 3));
        assertThat(redis.hasKey(key())).isFalse();

        cache.offer(campaignId, standing(teamId, 3));
        assertThat(disabledCache.find(campaignId, teamId)).isEmpty();
        disabledCache.invalidate(campaignId, teamId, 4);
        assertThat(cache.find(campaignId, teamId)).isEmpty();
    }

    @Test
    void unreachableRedisIsAMissAndNeverAnError() {
        try (UnreachableRedis unreachable = new UnreachableRedis(clock)) {
            RedisTeamStandingCache unreachableCache =
                    new RedisTeamStandingCache(unreachable.template, jsonMapper, cacheProperties, unreachable.circuitBreaker);

            assertThatCode(() -> {
                assertThat(unreachableCache.find(campaignId, teamId)).isEmpty();
                unreachableCache.offer(campaignId, standing(teamId, 3));
                unreachableCache.invalidate(campaignId, teamId, 4);
            }).doesNotThrowAnyException();
            assertThat(unreachable.circuitBreaker.isOpen()).isTrue();
        }
    }

    private String key() {
        return RedisKeys.team(campaignId, teamId);
    }

    private long ttlMillis() {
        return redis.getExpire(key(), TimeUnit.MILLISECONDS);
    }

    private static TeamStanding standing(long teamId, long version) {
        return new TeamStanding(teamId, 2, (int) version, 300, false, version, List.of(
                new MemberStanding(1, "alice", (int) version, false),
                new MemberStanding(2, "bob", 0, false)));
    }

    private static void runConcurrently(List<Runnable> operations) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(operations.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable operation : operations) {
                futures.add(pool.submit(() -> {
                    await(start);
                    operation.run();
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
