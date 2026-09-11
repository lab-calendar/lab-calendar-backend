package com.labcalendar.labcalendarbackend;

import java.util.List;
import java.util.Map;
import jakarta.servlet.RequestDispatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.labcalendar.labcalendarbackend.common.api.ApiErrorController;
import com.labcalendar.labcalendarbackend.common.api.ApiResponse;
import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;
import com.labcalendar.labcalendarbackend.common.exception.GlobalExceptionHandler;
import com.labcalendar.labcalendarbackend.health.HealthController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@Import({ApiContractTests.FixtureController.class, GlobalExceptionHandler.class, ApiErrorController.class, HealthController.class})
class ApiContractTests {
    @Autowired MockMvc mvc;

    @Test void successObjectAndListAreWrappedOnce() throws Exception {
        mvc.perform(get("/api/contract/object")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("meeting"))
                .andExpect(jsonPath("$.data.data").doesNotExist());
        mvc.perform(get("/api/contract/list")).andExpect(jsonPath("$.data").isArray());
    }

    @Test void createdAndNoContentPreserveHttpSemantics() throws Exception {
        mvc.perform(post("/api/contract/input").contentType("application/json").content("{\"title\":\"meeting\",\"leadTimeDays\":0}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.leadTimeDays").value(0));
        mvc.perform(delete("/api/contract/input")).andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test void requestValidationUsesJsonFieldNames() throws Exception {
        mvc.perform(post("/api/contract/input").contentType("application/json").content("{\"title\":\"\",\"leadTimeDays\":183}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.title").value("제목을 입력해 주세요."))
                .andExpect(jsonPath("$.fieldErrors.leadTimeDays").value("182일 이하여야 합니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test void missingRequiredValueIsAFieldError() throws Exception {
        mvc.perform(post("/api/contract/input").contentType("application/json").content("{\"title\":\"meeting\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.leadTimeDays").exists());
    }

    @Test void malformedAndWrongTypeJsonAreSanitized() throws Exception {
        for (String input : new String[]{"{", "{\"title\":\"meeting\",\"leadTimeDays\":\"SECRET\"}"}) {
            mvc.perform(post("/api/contract/input").contentType("application/json").content(input))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(content().string(not(containsString("SECRET"))))
                    .andExpect(jsonPath("$.trace").doesNotExist());
        }
    }

    @Test void invalidQueryConstraintReportsExternalParameterName() throws Exception {
        mvc.perform(get("/api/contract/query").param("days", "-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.days").value("0일 이상이어야 합니다."));
    }

    @Test void missingQueryAndWrongPathTypeAre400() throws Exception {
        mvc.perform(get("/api/contract/query")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/contract/id/abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void businessFailuresHaveExpectedStatusAndStableCode() throws Exception {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.UNAUTHORIZED, ErrorCode.FORBIDDEN, ErrorCode.NOT_FOUND, ErrorCode.CONFLICT}) {
            mvc.perform(get("/api/contract/business/" + code.name()))
                    .andExpect(status().is(code.status().value()))
                    .andExpect(jsonPath("$.code").value(code.name()))
                    .andExpect(jsonPath("$.message").value(code.message()));
        }
    }

    @Test void unexpectedExceptionDoesNotLeakInternals() throws Exception {
        mvc.perform(get("/api/contract/crash")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("SECRET"))))
                .andExpect(jsonPath("$.trace").doesNotExist()).andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test void frameworkStatusReasonIsNotExposed() throws Exception {
        mvc.perform(get("/api/contract/status")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("SECRET"))));
    }

    @Test void unknownRouteAndUnsupportedMethodUseErrorContract() throws Exception {
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/contract/object")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test void unsupportedContentTypeKeeps415() throws Exception {
        mvc.perform(post("/api/contract/input").contentType("text/plain").content("hello"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test void servletErrorDispatchDoesNotLeakAttributes() throws Exception {
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_MESSAGE, "SECRET")
                .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("SECRET")))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("SECRET"))));
    }

    @Test void healthProbeStaysBackwardCompatible() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"ok\"}"));
    }

    @RestController
    @RequestMapping("/api/contract")
    static class FixtureController {
        record Input(@NotBlank(message="제목을 입력해 주세요.") String title,
                @NotNull(message="준비 기간을 입력해 주세요.")
                @Min(value=0, message="0일 이상이어야 합니다.")
                @Max(value=182, message="182일 이하여야 합니다.") Integer leadTimeDays) {}

        @GetMapping("/object") ApiResponse<Map<String,String>> object() { return ApiResponse.of(Map.of("title","meeting")); }
        @GetMapping("/list") ApiResponse<List<String>> list() { return ApiResponse.of(List.of("meeting")); }
        @PostMapping("/input") ResponseEntity<ApiResponse<Input>> input(@Valid @RequestBody Input input) {
            return ResponseEntity.status(201).body(ApiResponse.of(input));
        }
        @DeleteMapping("/input") ResponseEntity<Void> remove() { return ResponseEntity.noContent().build(); }
        @GetMapping("/query") ApiResponse<Integer> query(@RequestParam("days") @Min(value=0,message="0일 이상이어야 합니다.") int value) { return ApiResponse.of(value); }
        @GetMapping("/id/{id}") ApiResponse<Long> id(@PathVariable("id") Long id) { return ApiResponse.of(id); }
        @GetMapping("/business/{code}") void business(@PathVariable("code") ErrorCode code) { throw new BusinessException(code); }
        @GetMapping("/crash") void crash() { throw new IllegalStateException("SECRET SQL credentials"); }
        @GetMapping("/status") void status() { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SECRET"); }
    }
}
