package com.labcalendar.labcalendarbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kan30prod;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.flyway.placeholders.tableOptions="
})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class OpenApiProdTests {
    @Autowired MockMvc mvc;

    @Test
    void hidesUiAndDocumentsInProduction() throws Exception {
        for (String path : new String[]{"/swagger-ui.html", "/swagger-ui/index.html",
                "/v3/api-docs", "/v3/api-docs/backend", "/v3/api-docs/swagger-config"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
}
