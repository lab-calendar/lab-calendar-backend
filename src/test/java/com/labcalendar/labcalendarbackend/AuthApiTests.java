package com.labcalendar.labcalendarbackend;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared-password sign in (KAN-34).
 *
 * <p>The hashes are produced here rather than checked in, so no fixture pretends to be the hash of
 * a password it is not.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTests {

    private static final String EDITOR_PASSWORD = "editor-password-for-tests";
    private static final String VIEWER_PASSWORD = "viewer-password-for-tests";
    private static final String COOKIE = "lab_calendar_session";

    @DynamicPropertySource
    static void authSettings(DynamicPropertyRegistry registry) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        registry.add("lab-calendar.auth.editor-password-hash", () -> encoder.encode(EDITOR_PASSWORD));
        registry.add("lab-calendar.auth.viewer-password-hash", () -> encoder.encode(VIEWER_PASSWORD));
    }

    @Autowired MockMvc mvc;

    private static String loginBody(String password) {
        return """
                {"password":"%s"}
                """.formatted(password);
    }

    @Test
    void editorPasswordGrantsEditorTier() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(loginBody(EDITOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.tier").value("EDITOR"))
                .andExpect(cookie().exists(COOKIE));
    }

    @Test
    void viewerPasswordGrantsViewerTier() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(loginBody(VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tier").value("VIEWER"));
    }

    @Test
    void sessionCookieIsHttpOnlyAndSameSiteLax() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(loginBody(EDITOR_PASSWORD)))
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andReturn();

        // Script must not be able to read it, and it must not ride along on cross-site requests.
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("SameSite=Lax");
    }

    @Test
    void wrongPasswordIsUnauthorizedAndSaysNothingAboutWhichOne() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(loginBody("neither-of-them")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(cookie().doesNotExist(COOKIE))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("EDITOR").doesNotContain("VIEWER")
                .doesNotContain("editor").doesNotContain("viewer");
    }

    @Test
    void blankPasswordIsAValidationError() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content(loginBody("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void meReportsTheTierOfTheSessionCookie() throws Exception {
        Cookie session = signIn(VIEWER_PASSWORD);

        mvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.tier").value("VIEWER"));
    }

    @Test
    void meWithoutACookieAnswersThatYouAreNotSignedIn() throws Exception {
        // Asking "am I signed in" and being told no is an answer, not a failure.
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(false))
                .andExpect(jsonPath("$.data.tier").doesNotExist());
    }

    @Test
    void aTamperedTokenIsNotAccepted() throws Exception {
        Cookie session = signIn(EDITOR_PASSWORD);
        String[] parts = session.getValue().split("\\.");

        // Same payload, signature from somewhere else.
        Cookie forged = new Cookie(COOKIE, parts[0] + ".aGVsbG8");
        mvc.perform(get("/api/auth/me").cookie(forged))
                .andExpect(jsonPath("$.data.authenticated").value(false));

        // Payload rewritten, original signature kept.
        Cookie upgraded = new Cookie(COOKIE, "RURJVE9SfDk5OTk5OTk5OTk" + "." + parts[1]);
        mvc.perform(get("/api/auth/me").cookie(upgraded))
                .andExpect(jsonPath("$.data.authenticated").value(false));
    }

    @Test
    void garbageInTheCookieIsIgnoredRatherThanFailing() throws Exception {
        for (String value : new String[]{"", ".", "no-separator", "a.b.c", "....."}) {
            mvc.perform(get("/api/auth/me").cookie(new Cookie(COOKIE, value)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.authenticated").value(false));
        }
    }

    @Test
    void logoutClearsTheCookie() throws Exception {
        mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(COOKIE, 0));
    }

    private Cookie signIn(String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(loginBody(password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(COOKIE);
    }
}
