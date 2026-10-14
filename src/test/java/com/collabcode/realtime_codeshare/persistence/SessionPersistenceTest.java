package com.collabcode.realtime_codeshare.persistence;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.service.SessionService;
import com.collabcode.realtime_codeshare.service.SessionService.JoinOutcome;
import com.collabcode.realtime_codeshare.service.SessionService.JoinResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A restart (new SessionService over the same repository) keeps rooms, tokens and roles. */
class SessionPersistenceTest {

    @Test
    void roomMembersAndRolesSurviveARestart() {
        SessionRepository repo = new InMemorySessionRepository();
        SessionService before = new SessionService(repo, 3, 30_000);
        Session s = before.createSession("Host", "#7aa2f7");
        String sid = s.getSessionId();
        JoinResult pending = before.evaluateJoin(sid, null, "g", "Guest", null);
        before.approve(sid, s.getCreatorToken(), pending.joinKey());
        JoinResult rejected = before.evaluateJoin(sid, null, "r", "Rude", null);
        before.reject(sid, s.getCreatorToken(), rejected.joinKey());

        SessionService after = new SessionService(repo, 3, 30_000); // the process restarted
        assertThat(after.exists(sid)).isTrue();
        assertThat(after.isAdmin(sid, s.getCreatorToken())).isTrue();
        assertThat(after.evaluateJoin(sid, pending.token(), "g2", "Guest", null).outcome()).isEqualTo(JoinOutcome.ADMITTED);
        assertThat(after.get(sid).getRejectedByToken()).containsEntry(rejected.token(), 1);
        assertThat(after.get(sid).getActiveOrder()).as("connection state is not durable").isEmpty();
    }

    @Test
    void endedAndRevokedSurviveARestart() {
        SessionRepository repo = new InMemorySessionRepository();
        SessionService before = new SessionService(repo, 3, 30_000);
        Session s = before.createSession("Host", "#7aa2f7");
        String sid = s.getSessionId();
        JoinResult j = before.evaluateJoin(sid, null, "g", "Guest", null);
        before.approve(sid, s.getCreatorToken(), j.joinKey());
        before.revoke(sid, j.token());

        SessionService after = new SessionService(repo, 3, 30_000);
        assertThat(after.isAdmitted(sid, j.token())).as("kicked stays kicked").isFalse();

        after.disconnectAll(sid);
        SessionService later = new SessionService(repo, 3, 30_000);
        assertThat(later.isEnded(sid)).isTrue();
    }

    @Test
    void reaperDeletesDurableRowsNobodyReloaded() {
        SessionRepository repo = new InMemorySessionRepository();
        SessionService before = new SessionService(repo, 3, 30_000);
        String idle = before.createSession("A", null).getSessionId();

        SessionService after = new SessionService(repo, 3, 30_000); // restart: nothing resident
        Session b = after.createSession("B", null);
        String fresh = b.getSessionId();
        after.markActive(fresh, b.getCreatorToken()); // someone is connected: never reaped
        List<String> reaped = after.reapIdle(1, System.currentTimeMillis() + 10); // TTL 1 ms: 'idle' is stale
        assertThat(reaped).contains(idle).doesNotContain(fresh);
        assertThat(after.exists(idle)).isFalse();
        assertThat(after.exists(fresh)).isTrue();
    }
}
