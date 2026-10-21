package io.github.eytoptas.teamchest.api.error;

import java.util.Arrays;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
public class ErrorMessages {

    private final MessageSource messageSource;

    public ErrorMessages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String of(ErrorCode code, Object... arguments) {
        return get(code.messageKey(), arguments);
    }

    public String get(String key, Object... arguments) {
        Object[] text = Arrays.stream(arguments).map(String::valueOf).toArray();
        return messageSource.getMessage(key, text, Locale.ROOT);
    }
}
