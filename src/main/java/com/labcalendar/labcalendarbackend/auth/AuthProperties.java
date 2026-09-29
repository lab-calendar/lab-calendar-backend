package com.labcalendar.labcalendarbackend.auth;

import java.time.Duration;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Shared-password settings (KAN-34). Values arrive from the environment; nothing is defaulted in
 * production, and no password or hash belongs in the repository.
 */
@Component
@ConfigurationProperties(prefix = "lab-calendar.auth")
public class AuthProperties {

    private static final int MINIMUM_SECRET_LENGTH = 32;

    /** BCrypt hash of the editing password. */
    private String editorPasswordHash;

    /** BCrypt hash of the read-only password. */
    private String viewerPasswordHash;

    /** Key the session token is signed with. Rotating it signs everyone out. */
    private String tokenSecret;

    /** How long a session lasts. Long enough for a working day, short enough to expire. */
    private Duration tokenTtl = Duration.ofHours(12);

    /** Off for plain-HTTP local development; on wherever the site is served over HTTPS. */
    private boolean cookieSecure = true;

    /**
     * Refuses to start on a misconfiguration rather than running with a hole in it.
     *
     * <p>Note what this can and cannot catch: two identical hash strings are a copy-paste, and
     * that is caught. Two <em>different</em> hashes of the <em>same</em> password are not — BCrypt
     * salts every hash, and the plaintext is never here to compare. Setting the two passwords to
     * the same text would silently give viewers editing rights, so that stays a human check
     * (KAN-37 documents the procedure).
     */
    @PostConstruct
    void validate() {
        require(editorPasswordHash, "lab-calendar.auth.editor-password-hash");
        require(viewerPasswordHash, "lab-calendar.auth.viewer-password-hash");
        require(tokenSecret, "lab-calendar.auth.token-secret");

        if (editorPasswordHash.equals(viewerPasswordHash)) {
            throw new IllegalStateException(
                    "편집용과 조회용 비밀번호 해시가 같습니다. 조회 등급이 편집 권한을 갖게 됩니다.");
        }
        if (tokenSecret.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "lab-calendar.auth.token-secret 은 " + MINIMUM_SECRET_LENGTH + "자 이상이어야 합니다.");
        }
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalStateException("lab-calendar.auth.token-ttl 은 0보다 커야 합니다.");
        }
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 가 설정되지 않았습니다.");
        }
    }

    public String getEditorPasswordHash() {
        return editorPasswordHash;
    }

    public void setEditorPasswordHash(String editorPasswordHash) {
        this.editorPasswordHash = editorPasswordHash;
    }

    public String getViewerPasswordHash() {
        return viewerPasswordHash;
    }

    public void setViewerPasswordHash(String viewerPasswordHash) {
        this.viewerPasswordHash = viewerPasswordHash;
    }

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }
}
