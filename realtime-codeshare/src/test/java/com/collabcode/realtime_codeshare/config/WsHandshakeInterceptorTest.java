package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.service.SessionService;
import com.collabcode.realtime_codeshare.ticket.Ticket;
import com.collabcode.realtime_codeshare.ticket.TicketStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The WebSocket auth gate: the handshake must redeem a valid, session-matched ticket
 * (token from the ticket, never the URL) and reject everything else.
 */
class WsHandshakeInterceptorTest {

    private static final String SID = "abcdefABCDEF0123"; // valid 16-char id

    private record Fixture(WsHandshakeInterceptor interceptor, MockHttpServletResponse rawResp,
                           ServletServerHttpRequest req, ServletServerHttpResponse resp,
                           Map<String, Object> attrs) {}

    private Fixture fixture(String query, SessionService ss, TicketStore ts) {
        MockHttpServletRequest mock = new MockHttpServletRequest("GET", "/ws/ot");
        mock.setScheme("http");
        mock.setServerName("localhost");
        mock.setServerPort(8080);
        mock.setQueryString(query);
        MockHttpServletResponse rawResp = new MockHttpServletResponse();
        return new Fixture(new WsHandshakeInterceptor(ss, ts), rawResp,
                new ServletServerHttpRequest(mock), new ServletServerHttpResponse(rawResp), new HashMap<>());
    }

    @Test
    void validTicketIsAdmittedAndSeedsTokenFromTicket() {
        SessionService ss = mock(SessionService.class);
        when(ss.exists(SID)).thenReturn(true);
        TicketStore ts = mock(TicketStore.class);
        when(ts.redeem("good")).thenReturn(new Ticket(SID, "tok-secret", "c1"));

        Fixture f = fixture("sessionId=" + SID + "&clientId=c1&name=Bob&ticket=good", ss, ts);
        boolean ok = f.interceptor().beforeHandshake(f.req(), f.resp(), null, f.attrs());

        assertThat(ok).isTrue();
        assertThat(f.attrs()).containsEntry("token", "tok-secret");
        assertThat(f.attrs()).containsEntry("clientId", "c1");
        assertThat(f.attrs()).containsEntry("sessionId", SID);
        assertThat(f.attrs()).containsEntry("name", "Bob");
    }

    @Test
    void missingOrInvalidTicketIsRejectedUnauthorized() {
        SessionService ss = mock(SessionService.class);
        when(ss.exists(SID)).thenReturn(true);
        TicketStore ts = mock(TicketStore.class);
        when(ts.redeem(null)).thenReturn(null);

        Fixture f = fixture("sessionId=" + SID + "&clientId=c1", ss, ts); // no ticket param
        boolean ok = f.interceptor().beforeHandshake(f.req(), f.resp(), null, f.attrs());

        assertThat(ok).isFalse();
        assertThat(f.rawResp().getStatus()).isEqualTo(401);
    }

    @Test
    void ticketForADifferentSessionIsRejected() {
        SessionService ss = mock(SessionService.class);
        when(ss.exists(SID)).thenReturn(true);
        TicketStore ts = mock(TicketStore.class);
        when(ts.redeem("x")).thenReturn(new Ticket("someOtherSession01", "tok", "c1"));

        Fixture f = fixture("sessionId=" + SID + "&clientId=c1&ticket=x", ss, ts);
        boolean ok = f.interceptor().beforeHandshake(f.req(), f.resp(), null, f.attrs());

        assertThat(ok).isFalse();
        assertThat(f.rawResp().getStatus()).isEqualTo(401);
    }

    @Test
    void unknownSessionIsRejectedNotFoundBeforeTicketCheck() {
        SessionService ss = mock(SessionService.class);
        when(ss.exists(SID)).thenReturn(false);
        TicketStore ts = mock(TicketStore.class);

        Fixture f = fixture("sessionId=" + SID + "&clientId=c1&ticket=whatever", ss, ts);
        boolean ok = f.interceptor().beforeHandshake(f.req(), f.resp(), null, f.attrs());

        assertThat(ok).isFalse();
        assertThat(f.rawResp().getStatus()).isEqualTo(404);
    }

    @Test
    void malformedSessionIdIsRejected() {
        SessionService ss = mock(SessionService.class);
        TicketStore ts = mock(TicketStore.class);
        Fixture f = fixture("sessionId=short&clientId=c1&ticket=x", ss, ts);
        boolean ok = f.interceptor().beforeHandshake(f.req(), f.resp(), null, f.attrs());
        assertThat(ok).isFalse();
        assertThat(f.rawResp().getStatus()).isEqualTo(404);
    }
}
