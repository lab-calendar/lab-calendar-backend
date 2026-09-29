package com.labcalendar.labcalendarbackend.support;

import jakarta.servlet.http.Cookie;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import com.labcalendar.labcalendarbackend.auth.AuthCookie;
import com.labcalendar.labcalendarbackend.auth.AuthTier;
import com.labcalendar.labcalendarbackend.auth.token.AuthTokenCodec;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Signs every request in a test class in as an editor (KAN-35).
 *
 * <p>The API needs a session now, so suites that are about something else — events, projects, the
 * roster — would otherwise have to attach a cookie to every single call. Import this and they read
 * as they did before.
 *
 * <p>Do not import it into tests that are about authentication or authorisation themselves: those
 * need to control who is calling, including nobody.
 */
@TestConfiguration
public class EditorSession {

    @Bean
    public MockMvcBuilderCustomizer editorCookie(AuthTokenCodec tokens) {
        Cookie session = new Cookie(AuthCookie.NAME, tokens.issue(AuthTier.EDITOR).value());
        // Cookies on the default request are merged into every request the suite makes.
        return builder -> builder.defaultRequest(get("/").cookie(session));
    }
}
