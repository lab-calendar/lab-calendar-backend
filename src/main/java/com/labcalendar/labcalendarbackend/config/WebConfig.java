package com.labcalendar.labcalendarbackend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.labcalendar.labcalendarbackend.auth.web.AuthInterceptor;

@Configuration
@ConfigurationProperties(prefix = "lab-calendar.web")
public class WebConfig implements WebMvcConfigurer {

    /**
     * Open without a session.
     *
     * <p>{@code /api/health} is the container's liveness probe and says nothing about the lab.
     * {@code /api/auth/**} has to be reachable to sign in at all, and it guards itself — the
     * password check and its rate limit live there.
     */
    private static final String[] PUBLIC_PATHS = {"/api/health", "/api/auth/**"};

    /**
     * Where a browser may call this API from.
     *
     * <p>Both deployments serve the frontend from the same origin as the API — nginx proxies
     * {@code /api/} in production, the Vite dev proxy does the same locally — so in practice
     * nothing needs this. It stays configured, and configurable, until that is confirmed end to
     * end; a wrong origin here is a blank calendar with a console error and no server-side trace.
     */
    private List<String> allowedOrigins = List.of("http://localhost:5173");

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(PUBLIC_PATHS);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // The session lives in a cookie, so the browser has to be allowed to send it.
                .allowCredentials(true);
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
