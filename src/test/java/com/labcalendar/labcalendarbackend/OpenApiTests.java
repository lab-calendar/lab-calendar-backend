package com.labcalendar.labcalendarbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiTests {
    @Autowired MockMvc mvc;

    @Test
    void exposesApiDocumentAndGroup() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Lab Calendar API"))
                .andExpect(jsonPath("$.paths['/api/health'].get.responses['200']").exists());
        mvc.perform(get("/v3/api-docs/backend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/health']").exists())
                .andExpect(jsonPath("$.paths['/error']").doesNotExist());
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
