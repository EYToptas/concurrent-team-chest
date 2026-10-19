package io.github.eytoptas.teamchest.application.idempotency;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

public final class OutcomeCodec<R> {

    private final JsonMapper jsonMapper;
    private final Map<String, Class<? extends R>> typesByCode;
    private final Map<Class<?>, String> codesByType = new HashMap<>();

    public OutcomeCodec(JsonMapper jsonMapper, Map<String, Class<? extends R>> typesByCode) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.typesByCode = Map.copyOf(typesByCode);
        this.typesByCode.forEach((code, type) -> {
            if (codesByType.put(type, code) != null) {
                throw new IllegalArgumentException("Type " + type.getSimpleName() + " is mapped to more than one code");
            }
        });
    }

    public StoredOutcome encode(R outcome) {
        String code = codesByType.get(outcome.getClass());
        if (code == null) {
            throw new IllegalArgumentException("No result code for " + outcome.getClass().getSimpleName());
        }
        return new StoredOutcome(code, jsonMapper.writeValueAsString(outcome));
    }

    public R decode(String code, String payload) {
        Class<? extends R> type = typesByCode.get(code);
        if (type == null) {
            throw new IllegalArgumentException("Unknown result code " + code);
        }
        return jsonMapper.readValue(payload, type);
    }

    public record StoredOutcome(String code, String payload) {
    }
}
