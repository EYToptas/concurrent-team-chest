package io.github.eytoptas.teamchest.api.ratelimit;

import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimited {

    RateLimitedOperation value();
}
