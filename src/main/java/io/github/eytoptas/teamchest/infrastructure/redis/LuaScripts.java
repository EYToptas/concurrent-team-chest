package io.github.eytoptas.teamchest.infrastructure.redis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

final class LuaScripts {

    private LuaScripts() {
    }

    static <T> RedisScript<T> load(String path, Class<T> resultType) {
        try {
            String text = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            RedisScript<T> script = RedisScript.of(text, resultType);
            script.getSha1();
            return script;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load Lua script " + path, e);
        }
    }
}
