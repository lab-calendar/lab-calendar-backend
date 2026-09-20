package com.labcalendar.labcalendarbackend.auth.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import com.labcalendar.labcalendarbackend.auth.AuthCookie;
import com.labcalendar.labcalendarbackend.auth.AuthTier;
import com.labcalendar.labcalendarbackend.auth.CurrentSession;
import com.labcalendar.labcalendarbackend.auth.service.AuthService;
import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;

/**
 * Requires a session on the API and keeps viewers out of anything that writes (KAN-35, KAN-21).
 *
 * <p>Failures are raised as {@link BusinessException} so they come back in the same error shape as
 * everything else — the handler advice turns them into the standard body.
 *
 * <p>This covers who may call what. What a caller is allowed to <em>see</em> is decided further in,
 * by the services, so a new query cannot come with its own way around it.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService auth;
    private final CurrentSession session;

    public AuthInterceptor(AuthService auth, CurrentSession session) {
        this.auth = auth;
        this.session = session;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // The browser sends preflight without cookies; rejecting it would break every cross-origin call.
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }

        AuthTier tier = auth.resolve(tokenFrom(request))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        session.authenticateAs(tier);

        if (isWrite(request.getMethod()) && tier != AuthTier.EDITOR) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return true;
    }

    /** Anything that can change stored data. Sync triggers are POSTs too, so they land here. */
    private static boolean isWrite(String method) {
        HttpMethod resolved = HttpMethod.valueOf(method);
        return HttpMethod.POST.equals(resolved)
                || HttpMethod.PUT.equals(resolved)
                || HttpMethod.PATCH.equals(resolved)
                || HttpMethod.DELETE.equals(resolved);
    }

    private static String tokenFrom(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookie.NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
