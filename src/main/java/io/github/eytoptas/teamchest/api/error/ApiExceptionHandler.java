package io.github.eytoptas.teamchest.api.error;

import io.github.eytoptas.teamchest.application.campaign.CampaignNotActiveException;
import io.github.eytoptas.teamchest.application.campaign.CampaignNotFoundException;
import io.github.eytoptas.teamchest.application.campaign.NoActiveCampaignException;
import io.github.eytoptas.teamchest.application.idempotency.IdempotencyKeyReuseException;
import io.github.eytoptas.teamchest.application.join.JoinBusyException;
import io.github.eytoptas.teamchest.application.transaction.ConcurrencyRetriesExhaustedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(RejectedRequestException.class)
    ResponseEntity<ErrorResponse> rejected(RejectedRequestException e) {
        return respond(ErrorResponse.of(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(CampaignNotFoundException.class)
    ResponseEntity<ErrorResponse> campaignNotFound(CampaignNotFoundException e) {
        return respond(ErrorResponse.of(ErrorCode.CAMPAIGN_NOT_FOUND, e.getMessage(), Map.of("campaignId", e.getCampaignId())));
    }

    @ExceptionHandler(NoActiveCampaignException.class)
    ResponseEntity<ErrorResponse> noActiveCampaign(NoActiveCampaignException e) {
        return respond(ErrorResponse.of(ErrorCode.NO_ACTIVE_CAMPAIGN, e.getMessage()));
    }

    @ExceptionHandler(CampaignNotActiveException.class)
    ResponseEntity<ErrorResponse> campaignNotActive(CampaignNotActiveException e) {
        return respond(ErrorResponse.of(ErrorCode.CAMPAIGN_NOT_ACTIVE, e.getMessage(),
                Map.of("campaignId", e.getCampaignId(), "phase", e.getPhase())));
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ResponseEntity<ErrorResponse> idempotencyKeyReused(IdempotencyKeyReuseException e) {
        return respond(ErrorResponse.of(ErrorCode.IDEMPOTENCY_KEY_REUSED, e.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ErrorResponse> rateLimited(RateLimitExceededException e) {
        long retryAfterSeconds = e.retryAfterSeconds();
        ErrorResponse body = ErrorResponse.of(ErrorCode.RATE_LIMITED, e.getMessage(),
                Map.of("operation", e.operation().key(), "retryAfterSeconds", retryAfterSeconds));
        return ResponseEntity.status(body.status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
                .body(body);
    }

    @ExceptionHandler(ConcurrencyRetriesExhaustedException.class)
    ResponseEntity<ErrorResponse> busy(ConcurrencyRetriesExhaustedException e) {
        return serviceBusy();
    }

    @ExceptionHandler(JoinBusyException.class)
    ResponseEntity<ErrorResponse> joinBusy(JoinBusyException e) {
        log.debug("Join not completed: {}", e.getMessage());
        return serviceBusy();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return validationFailed(errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> invalidParameters(HandlerMethodValidationException e) {
        List<String> errors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> describe(result.getMethodParameter().getParameterName(), error)))
                .toList();
        return validationFailed(errors);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException e) {
        return validationFailed(List.of("Missing request header " + e.getHeaderName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return validationFailed(List.of(e.getName() + ": invalid value"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return respond(ErrorResponse.of(ErrorCode.MALFORMED_REQUEST, "The request body is missing or is not valid JSON"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(ErrorResponse.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return respond(ErrorResponse.of(ErrorCode.NOT_FOUND, "No such endpoint"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unexpected error while handling a request", e);
        return respond(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Internal error"));
    }

    private static ResponseEntity<ErrorResponse> validationFailed(List<String> errors) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("errors", errors);
        return respond(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, "The request is not valid", details));
    }

    private static String describe(String parameter, MessageSourceResolvable error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : parameter;
        return field + ": " + error.getDefaultMessage();
    }

    private static ResponseEntity<ErrorResponse> serviceBusy() {
        ErrorResponse body = ErrorResponse.of(ErrorCode.SERVICE_BUSY, "The request could not be completed, retry later");
        return ResponseEntity.status(body.status()).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS).body(body);
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorResponse body) {
        return ResponseEntity.status(body.status()).body(body);
    }
}
