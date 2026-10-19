package io.github.eytoptas.teamchest.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateLimitExceededExceptionTest {

    @ParameterizedTest
    @CsvSource({"1, 1", "999, 1", "1000, 1", "1001, 2", "999999, 1000"})
    void retryAfterIsRoundedUpToWholeSeconds(long millis, long expectedSeconds) {
        RateLimitExceededException exception =
                new RateLimitExceededException(RateLimitedOperation.PROGRESS, Duration.ofMillis(millis));

        assertThat(exception.retryAfterSeconds()).isEqualTo(expectedSeconds);
    }
}
