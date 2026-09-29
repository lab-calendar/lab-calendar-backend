package com.labcalendar.labcalendarbackend.expense.importing;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.labcalendar.labcalendarbackend.common.api.ApiResponse;

@RestController
public class ImportHistoryController {
    private final ImportHistoryService service;

    public ImportHistoryController(ImportHistoryService service) { this.service = service; }

    @GetMapping("/api/card-expenses/imports")
    public ApiResponse<List<ImportHistoryService.History>> list(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.of(service.list(limit));
    }
}
