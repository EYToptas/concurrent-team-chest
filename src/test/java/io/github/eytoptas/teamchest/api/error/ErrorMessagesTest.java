package io.github.eytoptas.teamchest.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

class ErrorMessagesTest {

    private final ErrorMessages messages = new ErrorMessages(messageSource());

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @Test
    void everyErrorCodeHasAMessage() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(messages.of(code, "x", "y")).isNotBlank().doesNotContain("error.");
        }
    }

    @Test
    void argumentsAreNotFormattedAsNumbers() {
        assertThat(messages.of(ErrorCode.CAMPAIGN_NOT_FOUND, 12345L)).isEqualTo("Campaign 12345 does not exist");
    }
}
