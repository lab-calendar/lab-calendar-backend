package com.labcalendar.labcalendarbackend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.labcalendar.labcalendarbackend.auth.dto.LoginRequest;
import com.labcalendar.labcalendarbackend.auth.dto.SessionResponse;
import com.labcalendar.labcalendarbackend.auth.service.AuthService;
import com.labcalendar.labcalendarbackend.common.api.ApiResponse;

/** Shared-password sign in (KAN-34). Enforcing the tier on other routes is KAN-35. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;
    private final AuthProperties properties;

    public AuthController(AuthService service, AuthProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<SessionResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        AuthService.Session session =
                service.login(request.password(), ClientAddress.of(httpRequest));
        ResponseCookie cookie = AuthCookie.issue(
                session.token().value(), session.token().ttl(), properties.isCookieSecure());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.of(SessionResponse.of(session.tier())));
    }

    /** Always succeeds: asking to be signed out when you already are is not an error. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        AuthCookie.clear(properties.isCookieSecure()).toString())
                .build();
    }

    /**
     * The caller's current tier, for the route guard.
     *
     * <p>200 with {@code authenticated: false} rather than 401 — this is the question "am I signed
     * in", and being told no is a perfectly good answer to it.
     */
    @GetMapping("/me")
    public ApiResponse<SessionResponse> me(
            @CookieValue(name = AuthCookie.NAME, required = false) String token) {
        return ApiResponse.of(service.resolve(token)
                .map(SessionResponse::of)
                .orElseGet(SessionResponse::anonymous));
    }

}
