package io.github.eytoptas.teamchest.api.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.eytoptas.teamchest.application.campaign.CampaignNotActiveException;
import io.github.eytoptas.teamchest.application.campaign.CampaignNotFoundException;
import io.github.eytoptas.teamchest.application.campaign.NoActiveCampaignException;
import io.github.eytoptas.teamchest.application.idempotency.IdempotencyKeyReuseException;
import io.github.eytoptas.teamchest.application.join.JoinBusyException;
import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import io.github.eytoptas.teamchest.application.transaction.ConcurrencyRetriesExhaustedException;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler(new ErrorMessages(messageSource())))
            .build();

    @Test
    void exhaustedRetriesAre503WithRetryAfter() throws Exception {
        mvc.perform(get("/busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"));
    }

    @Test
    void busyJoinQueueIs503WithRetryAfter() throws Exception {
        mvc.perform(get("/join-busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
                .andExpect(content().string(not(containsString("waiting in this instance"))));
    }

    @Test
    void rateLimitedIs429WithRetryAfterInWholeSeconds() throws Exception {
        mvc.perform(get("/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.operation").value("progress"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").value(2));
    }

    @Test
    void unexpectedFailuresAre500WithoutInternals() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void reusedIdempotencyKeyIs422() throws Exception {
        mvc.perform(get("/reused"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void campaignErrorsCarryTheirDetails() throws Exception {
        mvc.perform(get("/unknown-campaign"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_FOUND"))
                .andExpect(jsonPath("$.details.campaignId").value(7));
        mvc.perform(get("/closed-campaign"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_ACTIVE"))
                .andExpect(jsonPath("$.details.phase").value("CLOSED"));
        mvc.perform(get("/no-campaign"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_CAMPAIGN"));
    }

    @Test
    void rawLockFailureIs500() throws Exception {
        mvc.perform(get("/lock")).andExpect(status().isInternalServerError());
    }

    @Test
    void errorWithoutDetailsOmitsTheDetailsField() throws Exception {
        mvc.perform(get("/no-campaign")).andExpect(jsonPath("$.details").doesNotExist());
    }

    @RestController
    static class FailingController {

        @GetMapping("/busy")
        void busy() {
            throw new ConcurrencyRetriesExhaustedException(3, new CannotAcquireLockException("deadlock"));
        }

        @GetMapping("/join-busy")
        void joinBusy() {
            throw new JoinBusyException("Too many joins are waiting in this instance");
        }

        @GetMapping("/rate-limited")
        void rateLimited() {
            throw new RateLimitExceededException(RateLimitedOperation.PROGRESS, Duration.ofMillis(1_500));
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret internals");
        }

        @GetMapping("/reused")
        void reused() {
            throw new IdempotencyKeyReuseException("key-1");
        }

        @GetMapping("/unknown-campaign")
        void unknownCampaign() {
            throw new CampaignNotFoundException(7);
        }

        @GetMapping("/closed-campaign")
        void closedCampaign() {
            throw new CampaignNotActiveException(7, CampaignPhase.CLOSED);
        }

        @GetMapping("/no-campaign")
        void noCampaign() {
            throw new NoActiveCampaignException();
        }

        @GetMapping("/lock")
        void lock() {
            throw new CannotAcquireLockException("deadlock");
        }
    }
}
