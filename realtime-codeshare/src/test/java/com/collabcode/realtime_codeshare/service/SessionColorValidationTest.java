package com.collabcode.realtime_codeshare.service;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.model.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server only stores a hex color; anything else falls back to the default
 * (defense-in-depth against a non-color string being persisted/relayed).
 */
class SessionColorValidationTest {

    private final SessionService service = new SessionService(3, 30_000);

    private String pendingColor(String requested) {
        Session s = service.createSession("Host", "#7aa2f7");
        SessionService.JoinResult jr =
                service.evaluateJoin(s.getSessionId(), null, "c1", "Guest", requested);
        User u = service.get(s.getSessionId()).getPendingByJoinKey().get(jr.joinKey());
        return u.getColor();
    }

    @Test
    void validHexColorsArePreserved() {
        assertThat(pendingColor("#abc")).isEqualTo("#abc");
        assertThat(pendingColor("#11AAff")).isEqualTo("#11AAff");
        assertThat(pendingColor("#11223344")).isEqualTo("#11223344");
    }

    @Test
    void nonHexColorsFallBackToDefault() {
        assertThat(pendingColor("red")).isEqualTo("#7aa2f7");
        assertThat(pendingColor("javascript:alert(1)")).isEqualTo("#7aa2f7");
        assertThat(pendingColor("")).isEqualTo("#7aa2f7");
        assertThat(pendingColor(null)).isEqualTo("#7aa2f7");
        assertThat(pendingColor("#xyz")).isEqualTo("#7aa2f7");
    }
}
