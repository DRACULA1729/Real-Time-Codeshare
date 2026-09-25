package com.collabcode.realtime_codeshare.service;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.model.User;
import com.collabcode.realtime_codeshare.security.Role;
import com.collabcode.realtime_codeshare.service.SessionService.JoinOutcome;
import com.collabcode.realtime_codeshare.service.SessionService.JoinResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the meet-style admission state machine and the confirmed admin transfer. */
class SessionServiceTest {

    private SessionService newService() {
        return new SessionService(3, 30_000); // max 3 rejected attempts, 30s admin offer
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

    /** Host + two approved joiners, all connected in admission order. Returns {sid, host, a, b}. */
    private String[] roomWithTwoJoiners(SessionService svc) {
        Session s = svc.createSession("Host", "#fff");
        String sid = s.getSessionId();
        String hostToken = s.getCreatorToken();
        JoinResult a = svc.evaluateJoin(sid, null, "ca", "A", "#0f0");
        svc.approve(sid, hostToken, a.joinKey());
        JoinResult b = svc.evaluateJoin(sid, null, "cb", "B", "#00f");
        svc.approve(sid, hostToken, b.joinKey());
        svc.markActive(sid, hostToken);
        svc.markActive(sid, a.token());
        svc.markActive(sid, b.token());
        return new String[] {sid, hostToken, a.token(), b.token()};
    }

    @Test
    void hostLeavingOffersAdminToLongestPresentMember_promotedOnlyOnAccept() {
        SessionService svc = newService();
        String[] r = roomWithTwoJoiners(svc);
        String sid = r[0], host = r[1], a = r[2], b = r[3];

        // Host disconnects -> A (longest-present) is offered the seat, not promoted.
        assertEquals(a, svc.markInactive(sid, host));
        assertFalse(svc.isAdmin(sid, a), "no silent escalation before accepting");
        assertTrue(svc.isAdmin(sid, host), "seat stays parked on the host token");

        assertFalse(svc.acceptAdmin(sid, b), "only the offered member can accept");
        assertTrue(svc.acceptAdmin(sid, a));
        assertTrue(svc.isAdmin(sid, a));
        assertFalse(svc.isAdmin(sid, host));
    }

    @Test
    void adminOfferMovesOnWhenDeclinedOrLapsed_andIsWithdrawnWhenHostReturns() {
        SessionService svc = newService();
        String[] r = roomWithTwoJoiners(svc);
        String sid = r[0], host = r[1], a = r[2], b = r[3];

        assertEquals(a, svc.markInactive(sid, host));
        assertEquals(b, svc.declineAdmin(sid, a), "decline passes to the next member");

        long now = System.currentTimeMillis();
        assertNull(svc.lapseAdminOffer(sid, b, now), "not expired yet -> no-op");
        assertEquals(b, svc.lapseAdminOffer(sid, b, now + 60_000),
                "lapse wraps past the decliner back to the only candidate left");

        // Host reconnects mid-offer: it simply resumes, the pending offer is withdrawn.
        assertEquals(b, svc.markActive(sid, host));
        assertFalse(svc.acceptAdmin(sid, b));
        assertTrue(svc.isAdmin(sid, host));
    }

    @Test
    void everyoneDeclining_seatIsOfferedAgainLater_andToAMemberWhoReconnects() {
        SessionService svc = newService();
        String[] r = roomWithTwoJoiners(svc);
        String sid = r[0], host = r[1], a = r[2], b = r[3];

        assertEquals(a, svc.markInactive(sid, host));
        assertEquals(b, svc.declineAdmin(sid, a));
        assertNull(svc.declineAdmin(sid, b), "everyone declined: no offer right now");
        assertEquals(a, svc.reofferVacantSeat(sid), "a later round starts again from the longest-present");
        assertNull(svc.reofferVacantSeat(sid), "only one offer out at a time");

        assertTrue(svc.acceptAdmin(sid, a));
        assertNull(svc.reofferVacantSeat(sid), "seat filled -> nothing to offer");
    }

    @Test
    void endedSessionRefusesEveryone_andAWaiterKeepsItsJoinKey() {
        SessionService svc = newService();
        Session s = svc.createSession("Host", "#fff");
        String sid = s.getSessionId();
        JoinResult w1 = svc.evaluateJoin(sid, null, "cw", "W", "#0f0");
        JoinResult w2 = svc.evaluateJoin(sid, w1.token(), "cw", "W", "#0f0"); // waiter reconnects
        assertEquals(w1.joinKey(), w2.joinKey(), "no duplicate request for the admin");

        svc.disconnectAll(sid);
        assertTrue(svc.isEnded(sid));
        assertEquals(JoinOutcome.DENIED, svc.evaluateJoin(sid, s.getCreatorToken(), "c", "Host", "#fff").outcome(),
                "even the host can't reopen an ended session");
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
