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

    private final ErrorMessages messages;

    public ApiExceptionHandler(ErrorMessages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(RejectedRequestException.class)
    ResponseEntity<ErrorResponse> rejected(RejectedRequestException e) {
        return respond(ErrorResponse.of(e.code(), messages.of(e.code()), e.details()));
    }

    @ExceptionHandler(CampaignNotFoundException.class)
    ResponseEntity<ErrorResponse> campaignNotFound(CampaignNotFoundException e) {
        return respond(error(ErrorCode.CAMPAIGN_NOT_FOUND, Map.of("campaignId", e.getCampaignId()), e.getCampaignId()));
    }

    @ExceptionHandler(NoActiveCampaignException.class)
    ResponseEntity<ErrorResponse> noActiveCampaign(NoActiveCampaignException e) {
        return respond(error(ErrorCode.NO_ACTIVE_CAMPAIGN));
    }

    @ExceptionHandler(CampaignNotActiveException.class)
    ResponseEntity<ErrorResponse> campaignNotActive(CampaignNotActiveException e) {
        return respond(error(ErrorCode.CAMPAIGN_NOT_ACTIVE, Map.of("campaignId", e.getCampaignId(), "phase", e.getPhase()),
                e.getCampaignId(), e.getPhase()));
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ResponseEntity<ErrorResponse> idempotencyKeyReused(IdempotencyKeyReuseException e) {
        return respond(error(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ErrorResponse> rateLimited(RateLimitExceededException e) {
        long retryAfterSeconds = e.retryAfterSeconds();
        ErrorResponse body = error(ErrorCode.RATE_LIMITED,
                Map.of("operation", e.operation().key(), "retryAfterSeconds", retryAfterSeconds), e.operation().key());
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
        return validationFailed(List.of(messages.get("validation.missing-header", e.getHeaderName())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return validationFailed(List.of(messages.get("validation.invalid-value", e.getName())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return respond(error(ErrorCode.MALFORMED_REQUEST));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(error(ErrorCode.METHOD_NOT_ALLOWED, Map.of(), e.getMethod()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(error(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return respond(error(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unexpected error while handling a request", e);
        return respond(error(ErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<ErrorResponse> validationFailed(List<String> errors) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("errors", errors);
        return respond(error(ErrorCode.VALIDATION_FAILED, details));
    }

    private ErrorResponse error(ErrorCode code) {
        return ErrorResponse.of(code, messages.of(code));
    }

    private ErrorResponse error(ErrorCode code, Map<String, Object> details, Object... arguments) {
        return ErrorResponse.of(code, messages.of(code, arguments), details);
    }

    private static String describe(String parameter, MessageSourceResolvable error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : parameter;
        return field + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> serviceBusy() {
        ErrorResponse body = error(ErrorCode.SERVICE_BUSY);
        return ResponseEntity.status(body.status()).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS).body(body);
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorResponse body) {
        return ResponseEntity.status(body.status()).body(body);
    }
}
