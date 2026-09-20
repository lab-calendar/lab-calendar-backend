package com.labcalendar.labcalendarbackend.auth;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup refuses a configuration that would leave a hole (KAN-34, KAN-37).
 *
 * <p>Failing to boot is the point: a server that runs with viewers holding editing rights is worse
 * than one that does not run at all.
 */
class AuthPropertiesTests {

    private static final String SECRET = "a-secret-long-enough-to-be-accepted!!";

    private static AuthProperties properties(String editorHash, String viewerHash, String secret) {
        AuthProperties properties = new AuthProperties();
        properties.setEditorPasswordHash(editorHash);
        properties.setViewerPasswordHash(viewerHash);
        properties.setTokenSecret(secret);
        return properties;
    }

    @Test
    void acceptsTwoDifferentHashesAndALongSecret() {
        assertThatCode(() -> properties("hash-editor", "hash-viewer", SECRET).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void refusesIdenticalHashes() {
        assertThatThrownBy(() -> properties("same-hash", "same-hash", SECRET).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("편집 권한");
    }

    @Test
    void refusesAMissingHash() {
        assertThatThrownBy(() -> properties("", "hash-viewer", SECRET).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties("hash-editor", "   ", SECRET).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAMissingOrShortSecret() {
        assertThatThrownBy(() -> properties("hash-editor", "hash-viewer", null).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties("hash-editor", "hash-viewer", "too-short").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void refusesANonPositiveLifetime() {
        AuthProperties zero = properties("hash-editor", "hash-viewer", SECRET);
        zero.setTokenTtl(Duration.ZERO);
        assertThatThrownBy(zero::validate).isInstanceOf(IllegalStateException.class);
    }
}
