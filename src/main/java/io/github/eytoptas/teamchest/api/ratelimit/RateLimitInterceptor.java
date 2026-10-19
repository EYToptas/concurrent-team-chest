package io.github.eytoptas.teamchest.api.ratelimit;

import io.github.eytoptas.teamchest.api.error.RateLimitExceededException;
import io.github.eytoptas.teamchest.application.ratelimit.RateLimitDecision;
import io.github.eytoptas.teamchest.application.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited rateLimited = handlerMethod.getMethodAnnotation(RateLimited.class);
        if (rateLimited == null) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return true;
        }
        RateLimitDecision decision = rateLimiter.tryAcquire(rateLimited.value(), jwt.getSubject());
        if (!decision.allowed()) {
            throw new RateLimitExceededException(rateLimited.value(), decision.retryAfter());
        }
        return true;
    }
}
