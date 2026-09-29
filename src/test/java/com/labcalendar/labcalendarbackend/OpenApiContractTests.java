package com.labcalendar.labcalendarbackend;

import com.labcalendar.labcalendarbackend.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(OpenApiContractTests.DocumentationFixture.class)
class OpenApiContractTests {
    @Autowired MockMvc mvc;

    @Test
    void documentsTypedWrapperAndReusableErrorsInBothDocuments() throws Exception {
        for (String path : new String[]{"/v3/api-docs", "/v3/api-docs/backend"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.code.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.fieldErrors.additionalProperties.type").value("string"))
                .andExpect(jsonPath("$.components.responses.VALIDATION_FAILED.content['application/json'].example.fieldErrors.title").value("제목을 입력해 주세요."))
                .andExpect(jsonPath("$.paths['/api/doc-fixture'].get.responses['500']['$ref']").value("#/components/responses/INTERNAL_ERROR"))
                .andExpect(jsonPath("$.components.schemas.ApiResponseDocItem.properties.data['$ref']").value("#/components/schemas/DocItem"))
                .andExpect(jsonPath("$.components.schemas.ApiResponseListDocItem.properties.data.type").value("array"))
                .andExpect(jsonPath("$.components.schemas.ApiResponseListDocItem.properties.data.items['$ref']").value("#/components/schemas/DocItem"))
                .andExpect(jsonPath("$.paths['/api/doc-fixture'].delete.responses['204'].content").doesNotExist());
        }
    }

    @RestController
    static class DocumentationFixture {
        @GetMapping("/api/doc-fixture")
        public ApiResponse<DocItem> item() { return ApiResponse.of(new DocItem("예시")); }
        @GetMapping("/api/doc-fixture/list")
        public ApiResponse<List<DocItem>> list() { return ApiResponse.of(List.of(new DocItem("예시"))); }
        @DeleteMapping("/api/doc-fixture")
        @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
        public ResponseEntity<Void> delete() { return ResponseEntity.noContent().build(); }
    }
    record DocItem(String title) {}
}
