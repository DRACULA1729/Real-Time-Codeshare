package com.collabcode.realtime_codeshare.service;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.model.User;
import com.collabcode.realtime_codeshare.security.Role;
import com.collabcode.realtime_codeshare.service.SessionService.JoinOutcome;
import com.collabcode.realtime_codeshare.service.SessionService.JoinResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the meet-style admission state machine and admin transfer. */
class SessionServiceTest {

    private SessionService newService() {
        return new SessionService(3); // max 3 rejected attempts
    }

    @Test
    void idFormatValidation() {
        assertTrue(SessionService.isValidIdFormat("abcdEFGH_1234567"));   // 16 chars
        assertFalse(SessionService.isValidIdFormat("tooShort"));
        assertFalse(SessionService.isValidIdFormat("has spaces and!!"));
        assertFalse(SessionService.isValidIdFormat(null));
    }

    @Test
    void createSession_mintsValidIdAndAdminToken() {
        SessionService svc = newService();
        Session s = svc.createSession("Host", "#fff");
        assertTrue(SessionService.isValidIdFormat(s.getSessionId()));
        assertNotNull(s.getCreatorToken());
        assertTrue(svc.exists(s.getSessionId()));
        assertTrue(svc.isAdmin(s.getSessionId(), s.getCreatorToken()));
    }

    @Test
    void creatorIsAdmittedAsCreator() {
        SessionService svc = newService();
        Session s = svc.createSession("Host", "#fff");
        JoinResult jr = svc.evaluateJoin(s.getSessionId(), s.getCreatorToken(), "c1", "Host", "#fff");
        assertEquals(JoinOutcome.ADMITTED, jr.outcome());
        assertEquals(Role.CREATOR, jr.role());
    }

    @Test
    void newJoinerIsPending_andGetsMintedToken() {
        SessionService svc = newService();
        Session s = svc.createSession("Host", "#fff");
        JoinResult jr = svc.evaluateJoin(s.getSessionId(), null, "c2", "Guest", "#0f0");
        assertEquals(JoinOutcome.PENDING, jr.outcome());
        assertNotNull(jr.token(), "a tokenless joiner must be minted one");
        assertNotNull(jr.joinKey());
        assertFalse(svc.isAdmitted(s.getSessionId(), jr.token()));
    }

    @Test
    void approvedJoinerReconnectsWithoutReapproval() {
        SessionService svc = newService();
        Session s = svc.createSession("Host", "#fff");
        JoinResult pending = svc.evaluateJoin(s.getSessionId(), null, "c2", "Guest", "#0f0");

        User approved = svc.approve(s.getSessionId(), s.getCreatorToken(), pending.joinKey());
        assertNotNull(approved);
        assertTrue(svc.isAdmitted(s.getSessionId(), pending.token()));

        // Reconnect with the same token -> straight to ADMITTED, no new join request.
        JoinResult reconnect = svc.evaluateJoin(s.getSessionId(), pending.token(), "c2", "Guest", "#0f0");
        assertEquals(JoinOutcome.ADMITTED, reconnect.outcome());
    }

    @Test
    void nonAdminCannotApprove() {
        SessionService svc = newService();
        Session s = svc.createSession("Host", "#fff");
        JoinResult pending = svc.evaluateJoin(s.getSessionId(), null, "c2", "Guest", "#0f0");
        assertNull(svc.approve(s.getSessionId(), "not-the-admin-token", pending.joinKey()));
        assertFalse(svc.isAdmitted(s.getSessionId(), pending.token()));
    }

    @Test
    void rejectionCapBlocksAfterThreeRejects() {
        SessionService svc = newService();
        Session s = svc.createSession("Host", "#fff");
        String token = null;
        for (int i = 0; i < 3; i++) {
            JoinResult jr = svc.evaluateJoin(s.getSessionId(), token, "c2", "Guest", "#0f0");
            assertEquals(JoinOutcome.PENDING, jr.outcome());
            token = jr.token();
            assertNotNull(svc.reject(s.getSessionId(), s.getCreatorToken(), jr.joinKey()));
        }
        // Fourth attempt with the same identity is denied outright.
        JoinResult blocked = svc.evaluateJoin(s.getSessionId(), token, "c2", "Guest", "#0f0");
        assertEquals(JoinOutcome.DENIED, blocked.outcome());
    }

    @Test
    void adminTransfersToLongestPresentRemainingMemberWhenHostLeaves() {
        SessionService svc = newService();
        Session s = svc.createSession("Host", "#fff");
        String sid = s.getSessionId();
        String hostToken = s.getCreatorToken();

        // Two joiners approved and connected, in order.
        JoinResult a = svc.evaluateJoin(sid, null, "ca", "A", "#0f0");
        svc.approve(sid, hostToken, a.joinKey());
        JoinResult b = svc.evaluateJoin(sid, null, "cb", "B", "#00f");
        svc.approve(sid, hostToken, b.joinKey());

        svc.markActive(sid, hostToken);
        svc.markActive(sid, a.token());
        svc.markActive(sid, b.token());

        // Host disconnects -> admin goes to the longest-present remaining member (A).
        String newAdmin = svc.markInactive(sid, hostToken);
        assertEquals(a.token(), newAdmin);
        assertTrue(svc.isAdmin(sid, a.token()));
        assertFalse(svc.isAdmin(sid, hostToken));
    }

    @Test
    void roomStaysAliveWhileMembersConnected_andReapsWhenEmptyAndIdle() {
        SessionService svc = newService();
        Session s = svc.createSession("Host", "#fff");
        String sid = s.getSessionId();
        svc.markActive(sid, s.getCreatorToken());

        long now = System.currentTimeMillis();
        // Connected -> never reaped regardless of age.
        assertTrue(svc.reapIdle(0, now + 10_000_000L).isEmpty());
        assertTrue(svc.exists(sid));

        // Disconnect, then sweep past TTL -> reaped.
        svc.markInactive(sid, s.getCreatorToken());
        assertTrue(svc.reapIdle(0, now + 10_000_000L).contains(sid));
        assertFalse(svc.exists(sid));
    }
}
