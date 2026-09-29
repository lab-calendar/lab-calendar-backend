package com.labcalendar.labcalendarbackend.common.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;

/** Covers servlet error dispatches that never reach controller advice. */
@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = value instanceof Integer code && code >= 400 && code <= 599 ? code : 500;
        return ResponseEntity.status(status).body(ApiError.of(ErrorCode.forStatus(status)));
    }
}
