package io.github.eytoptas.teamchest.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(int status, String code, String message, Map<String, Object> details) {

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.status(), code.name(), message, Map.of());
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details) {
        return new ErrorResponse(code.status(), code.name(), message, details);
    }
}
