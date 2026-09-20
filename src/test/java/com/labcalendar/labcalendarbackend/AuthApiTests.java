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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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

    @Test
    void rewritingForwardedForDoesNotResetTheFailureCount() throws Exception {
        // nginx 는 X-Forwarded-For 에 덧붙이기만 하므로 앞부분은 호출자가 마음대로 정한다.
        // 그 값으로 집계하면 매 요청 다른 키가 되어 제한이 무력해진다.
        String caller = "203.0.113.9";
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/auth/login").contentType("application/json")
                            .header("X-Real-IP", caller)
                            .header("X-Forwarded-For", "10.9.9." + attempt + ", " + caller)
                            .content(loginBody("wrong-" + attempt)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .header("X-Real-IP", caller)
                        .header("X-Forwarded-For", "10.9.9.99, " + caller)
                        .content(loginBody("wrong-again")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aBlockedCallerCannotGetInWithTheRightPasswordEither() throws Exception {
        String caller = "203.0.113.10";
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/auth/login").contentType("application/json")
                            .header("X-Real-IP", caller).content(loginBody("wrong-" + attempt)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .header("X-Real-IP", caller).content(loginBody(EDITOR_PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void anotherCallerIsUnaffectedByAnAddressBeingBlocked() throws Exception {
        String blocked = "203.0.113.11";
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/auth/login").contentType("application/json")
                            .header("X-Real-IP", blocked).content(loginBody("wrong-" + attempt)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .header("X-Real-IP", "203.0.113.12").content(loginBody(EDITOR_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void theHeaderIsIgnoredWhenTheRequestDidNotComeThroughTheProxy() throws Exception {
        // 프록시를 건너뛰고 직접 들어온 요청은 스스로 붙인 헤더를 신뢰받지 못한다.
        String publicPeer = "198.51.100.7";
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/auth/login").contentType("application/json")
                            .with(fromAddress(publicPeer))
                            .header("X-Real-IP", "10.0.0." + attempt)
                            .content(loginBody("wrong-" + attempt)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .with(fromAddress(publicPeer))
                        .header("X-Real-IP", "10.0.0.99")
                        .content(loginBody("wrong-again")))
                .andExpect(status().isTooManyRequests());
    }

    private static RequestPostProcessor fromAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private Cookie signIn(String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(loginBody(password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(COOKIE);
    }
}
