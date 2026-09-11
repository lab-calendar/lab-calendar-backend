package com.labcalendar.labcalendarbackend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI labCalendarOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Lab Calendar API")
                .version("v1")
                .description("랩 캘린더 백엔드 API. 현재 구현된 엔드포인트만 표시합니다."));
    }

    @Bean
    GroupedOpenApi backendApi() {
        return GroupedOpenApi.builder().group("backend").pathsToMatch("/api/**").build();
    }
}
