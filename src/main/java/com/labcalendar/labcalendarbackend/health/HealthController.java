package com.labcalendar.labcalendarbackend.health;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health", description = "서버 연결 확인")
public class HealthController {
    @Operation(summary = "서버 연결 확인", description = "기존 프론트 호환을 위해 data 래퍼 없이 status를 반환합니다.")
    @ApiResponse(responseCode = "200", description = "서버 응답 정상", useReturnTypeSchema = true,
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"status\":\"ok\"}")))
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
