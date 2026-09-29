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

    @ExceptionHandler(com.labcalendar.labcalendarbackend.expense.importing.ImportApiException.class)
    public ResponseEntity<ApiError> importFailure(com.labcalendar.labcalendarbackend.expense.importing.ImportApiException ex) {
        String message = switch (ex.code()) {
            case "FILE_TOO_LARGE" -> "엑셀 파일은 5 MiB 이하로 업로드해 주세요.";
            case "PREVIEW_TOKEN_REQUIRED" -> "먼저 미리보기를 확인한 뒤 반영해 주세요.";
            case "PREVIEW_TOKEN_INVALID" -> "미리보기 확인 정보가 올바르지 않습니다. 다시 미리보기해 주세요.";
            case "PREVIEW_STALE" -> "미리보기가 만료되었거나 내용이 변경되었습니다. 다시 미리보기해 주세요.";
            case "INVALID_FILE_NAME" -> "파일 이름은 191자 이하로 지정해 주세요.";
            case "INVALID_WORKBOOK" -> "엑셀 파일을 읽을 수 없습니다. 정상적인 .xlsx 파일을 다시 선택해 주세요.";
            default -> "파일과 미리보기 정보를 확인해 주세요.";
        };
        return ResponseEntity.status(ex.status()).body(new ApiError(ex.code(), message, Map.of()));
    }

    @ExceptionHandler(com.labcalendar.labcalendarbackend.expense.importing.ExcelReadException.class)
    public ResponseEntity<ApiError> excelFailure(com.labcalendar.labcalendarbackend.expense.importing.ExcelReadException ex) {
        int status = ex.code() == com.labcalendar.labcalendarbackend.expense.importing.ExcelReadException.Code.FILE_TOO_LARGE ? 413 : 400;
        return ResponseEntity.status(status).body(new ApiError(ex.code().name(),
                status == 413 ? "엑셀 파일은 5 MiB 이하로 업로드해 주세요." : "엑셀 파일의 형식과 내용을 확인해 주세요.", Map.of()));
    }

    @ExceptionHandler(com.labcalendar.labcalendarbackend.expense.importing.LedgerSyncException.class)
    public ResponseEntity<ApiError> syncFailure(com.labcalendar.labcalendarbackend.expense.importing.LedgerSyncException ex) {
        int status = switch (ex.code()) {
            case NO_APPLICABLE_MONTHS -> 422;
            case UNSUPPORTED_DATABASE_YEAR, ROW_MONTH_MISMATCH, DUPLICATE_MONTH, NO_MONTHS -> 400;
            default -> 500;
        };
        return ResponseEntity.status(status).body(new ApiError(ex.code().name(),
                status == 422 ? "반영 가능한 월이 없습니다. 오류 행을 수정해 주세요." : "카드 내역을 반영하지 못했습니다.", Map.of()));
    }

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
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, new ApiError("FILE_TOO_LARGE",
                "엑셀 파일은 5 MiB 이하, 전체 업로드 요청은 6 MiB 이하로 보내 주세요.", Map.of()),
                headers, status, request);
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
