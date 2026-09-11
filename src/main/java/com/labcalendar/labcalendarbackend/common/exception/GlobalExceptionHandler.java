package com.labcalendar.labcalendarbackend.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import com.labcalendar.labcalendarbackend.common.api.ApiError;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException exception) {
        return ResponseEntity.status(exception.errorCode().status()).body(ApiError.of(exception.errorCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("Unhandled API failure", exception);
        return ResponseEntity.internalServerError().body(ApiError.of(ErrorCode.INTERNAL_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), safeMessage(error.getDefaultMessage())));
        return handleExceptionInternal(ex, ApiError.of(ErrorCode.VALIDATION_FAILED, fields), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.isForReturnValue()) return handleExceptionInternal(ex, null, headers, status, request);
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = requestName(result.getMethodParameter());
            result.getResolvableErrors().forEach(error -> fields.putIfAbsent(name, safeMessage(error.getDefaultMessage())));
        });
        return handleExceptionInternal(ex, ApiError.of(ErrorCode.VALIDATION_FAILED, fields), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) log.error("API framework failure", ex);
        Object safeBody = body instanceof ApiError ? body : ApiError.of(ErrorCode.forStatus(status.value()));
        // Keep framework status and headers (e.g. Allow on a 405), replace only its error body.
        return super.handleExceptionInternal(ex, safeBody, headers, status, request);
    }

    private static String safeMessage(String message) {
        return message == null ? "입력값을 확인해 주세요." : message;
    }

    private static String requestName(MethodParameter parameter) {
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null) {
            if (!query.name().isBlank()) return query.name();
            if (!query.value().isBlank()) return query.value();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null) {
            if (!path.name().isBlank()) return path.name();
            if (!path.value().isBlank()) return path.value();
        }
        return parameter.getParameterName() == null ? "parameter" : parameter.getParameterName();
    }
}
