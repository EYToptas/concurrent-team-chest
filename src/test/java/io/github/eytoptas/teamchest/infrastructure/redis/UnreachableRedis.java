package io.github.eytoptas.teamchest.infrastructure.redis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

final class UnreachableRedis implements AutoCloseable {

    private final LettuceConnectionFactory connectionFactory;
    final StringRedisTemplate template;
    final RedisCircuitBreaker circuitBreaker;

    UnreachableRedis(Clock clock) {
        circuitBreaker = new RedisCircuitBreaker(clock, Duration.ofSeconds(30));
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", unusedPort()),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build());
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        template = new StringRedisTemplate(connectionFactory);
    }

    @Override
    public void close() {
        connectionFactory.destroy();
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
