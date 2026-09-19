package com.labcalendar.labcalendarbackend.config;

import com.labcalendar.labcalendarbackend.common.api.ApiError;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI labCalendarOpenApi() {
        return new OpenAPI().info(new Info().title("Lab Calendar API").version("v1")
                .description("구현된 API만 표시합니다. 업무 성공 응답은 data 래퍼, 오류는 code/message/fieldErrors 형식입니다. /api/health는 기존 status 응답을 유지합니다."));
    }

    @Bean
    GroupedOpenApi backendApi() {
        return GroupedOpenApi.builder().group("backend").pathsToMatch("/api/**").build();
    }

    @Bean
    GlobalOpenApiCustomizer commonErrorDocumentation() {
        return api -> {
            if (api.getComponents() == null) api.setComponents(new Components());
            ModelConverters.getInstance().read(ApiError.class).forEach(api.getComponents()::addSchemas);
            for (ErrorCode code : ErrorCode.values()) {
                api.getComponents().addResponses(code.name(), new ApiResponse()
                        .description(code.status().value() + ": " + code.message())
                        .content(new Content().addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiError"))
                                .example(ApiError.of(code, code == ErrorCode.VALIDATION_FAILED
                                        ? java.util.Map.of("title", "제목을 입력해 주세요.") : java.util.Map.of())))));
            }
            if (api.getPaths() != null) api.getPaths().forEach((path, item) -> {
                if (path.startsWith("/api/")) item.readOperations().forEach(operation ->
                        operation.getResponses().putIfAbsent("500", new ApiResponse()
                                .$ref("#/components/responses/INTERNAL_ERROR")));
            });
        };
    }
}
