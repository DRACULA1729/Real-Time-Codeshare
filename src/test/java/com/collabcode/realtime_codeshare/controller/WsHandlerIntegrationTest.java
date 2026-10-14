package com.collabcode.realtime_codeshare.controller;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.ot.OTService;
import com.collabcode.realtime_codeshare.persistence.DocumentRepository;
import com.collabcode.realtime_codeshare.persistence.StoredDocument;
import com.collabcode.realtime_codeshare.service.SessionService;
import com.collabcode.realtime_codeshare.ticket.TicketStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end exercise of the WebSocket handler state machine over a real socket:
 * ticket-gated admission, waiting room + host approval, op broadcast, and rejection
 * of a ticket-less handshake. Tickets are issued through the real {@link TicketStore}
 * so the handshake auth gate is covered too.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class WsHandlerIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    SessionService sessionService;
    @Autowired
    TicketStore ticketStore;
    @Autowired
    OTService otService;
    @Autowired
    DocumentRepository repository;

    private final ObjectMapper mapper = new ObjectMapper();

    static class Collector extends TextWebSocketHandler {
        final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        @Override
        public void handleTextMessage(WebSocketSession s, TextMessage m) {
            q.add(m.getPayload());
        }
        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            // A terminal type may arrive only as the close reason (see closeAll).
            if (status.getReason() != null) q.add("{\"type\":\"" + status.getReason() + "\"}");
        }
    }

    private WebSocketSession connect(String sid, String clientId, String name, String ticket, Collector c) throws Exception {
        String url = "ws://localhost:" + port + "/ws/ot?sessionId=" + sid
                + "&clientId=" + clientId + "&name=" + name + "&ticket=" + ticket;
        return new StandardWebSocketClient().execute(c, url).get(5, TimeUnit.SECONDS);
    }

    private JsonNode waitFor(Collector c, String type) throws Exception {
        return waitFor(c, type, 5000);
    }

    private JsonNode waitFor(Collector c, String type, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String msg = c.q.poll(200, TimeUnit.MILLISECONDS);
            if (msg == null) continue;
            JsonNode n = mapper.readTree(msg);
            if (type.equals(n.path("type").asText())) return n;
        }
        return null;
    }

    @Test
    void admitApproveAndBroadcast() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();
        String adminToken = session.getCreatorToken();

        // Host connects with an admin ticket -> admitted as CREATOR.
        Collector hostC = new Collector();
        WebSocketSession host = connect(sid, "host", "Host", ticketStore.issue(sid, adminToken, "host"), hostC);
        JsonNode init = waitFor(hostC, "init");
        assertThat(init).as("host init").isNotNull();
        assertThat(init.path("role").asText()).isEqualTo("CREATOR");
        assertThat(init.path("document").asText()).isEmpty();

        // Joiner with no token -> waiting room; host is notified.
        Collector guestC = new Collector();
        WebSocketSession guest = connect(sid, "guest", "Guest", ticketStore.issue(sid, null, "guest"), guestC);
        assertThat(waitFor(guestC, "waitingForApproval")).as("guest waiting").isNotNull();
        JsonNode jr = waitFor(hostC, "joinRequest");
        assertThat(jr).as("host sees join request").isNotNull();

        // Host approves -> guest is admitted.
        host.sendMessage(new TextMessage("{\"type\":\"approve\",\"joinKey\":\"" + jr.path("joinKey").asText() + "\"}"));
        assertThat(waitFor(guestC, "init")).as("guest admitted").isNotNull();

        // Host edits -> the op is broadcast to the guest.
        host.sendMessage(new TextMessage("{\"type\":\"insert\",\"clientId\":\"host\",\"baseVersion\":0,\"pos\":0,\"text\":\"hi\"}"));
        JsonNode op = waitFor(guestC, "op");
        assertThat(op).as("guest receives op").isNotNull();
        assertThat(op.path("op").path("text").asText()).isEqualTo("hi");
        assertThat(op.has("hash")).as("no per-op hash broadcast").isFalse();

        host.close(CloseStatus.NORMAL);
        guest.close(CloseStatus.NORMAL);
    }

    @Test
    void renameIsBroadcastAndDeliveredOnInit() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();
        String adminToken = session.getCreatorToken();

        Collector hostC = new Collector();
        WebSocketSession host = connect(sid, "host", "Host", ticketStore.issue(sid, adminToken, "host"), hostC);
        JsonNode init = waitFor(hostC, "init");
        assertThat(init).isNotNull();
        assertThat(init.path("fileName").asText()).as("fresh room starts unnamed").isEmpty();

        Collector guestC = new Collector();
        WebSocketSession guest = connect(sid, "guest", "Guest", ticketStore.issue(sid, null, "guest"), guestC);
        assertThat(waitFor(guestC, "waitingForApproval")).isNotNull();
        JsonNode jr = waitFor(hostC, "joinRequest");
        host.sendMessage(new TextMessage("{\"type\":\"approve\",\"joinKey\":\"" + jr.path("joinKey").asText() + "\"}"));
        assertThat(waitFor(guestC, "init")).isNotNull();

        host.sendMessage(new TextMessage("{\"type\":\"rename\",\"fileName\":\"MyClass\"}"));
        JsonNode guestRename = waitFor(guestC, "rename");
        assertThat(guestRename).as("guest receives rename").isNotNull();
        assertThat(guestRename.path("fileName").asText()).isEqualTo("MyClass");
        assertThat(waitFor(hostC, "rename")).as("sender receives the echo too").isNotNull();

        // A late joiner (token holder, auto-admitted) gets the name on init.
        Collector lateC = new Collector();
        WebSocketSession late = connect(sid, "late", "Late", ticketStore.issue(sid, adminToken, "late"), lateC);
        JsonNode lateInit = waitFor(lateC, "init");
        assertThat(lateInit).isNotNull();
        assertThat(lateInit.path("fileName").asText()).isEqualTo("MyClass");

        host.close(CloseStatus.NORMAL);
        guest.close(CloseStatus.NORMAL);
        late.close(CloseStatus.NORMAL);
    }

    @Test
    void languageChoiceIsSanitizedBroadcastAndDeliveredOnInit() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();
        String adminToken = session.getCreatorToken();

        Collector hostC = new Collector();
        WebSocketSession host = connect(sid, "host", "Host", ticketStore.issue(sid, adminToken, "host"), hostC);
        JsonNode init = waitFor(hostC, "init");
        assertThat(init.path("language").asText()).as("fresh room auto-detects").isEmpty();

        host.sendMessage(new TextMessage("{\"type\":\"setLanguage\",\"language\":\"Tantu\"}"));
        JsonNode echo = waitFor(hostC, "language");
        assertThat(echo).as("sender receives the echo").isNotNull();
        assertThat(echo.path("language").asText()).isEqualTo("tantu");

        // A late joiner gets the chosen language on init.
        Collector lateC = new Collector();
        WebSocketSession late = connect(sid, "late", "Late", ticketStore.issue(sid, adminToken, "late"), lateC);
        assertThat(waitFor(lateC, "init").path("language").asText()).isEqualTo("tantu");

        // Anything outside the id shape falls back to auto-detect.
        host.sendMessage(new TextMessage("{\"type\":\"setLanguage\",\"language\":\"<script>\"}"));
        assertThat(waitFor(lateC, "language").path("language").asText()).isEmpty();

        host.close(CloseStatus.NORMAL);
        late.close(CloseStatus.NORMAL);
    }

    @Test
    void renameFromNonAdmittedClientIsDropped() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();

        Collector hostC = new Collector();
        WebSocketSession host = connect(sid, "host", "Host",
                ticketStore.issue(sid, session.getCreatorToken(), "host"), hostC);
        assertThat(waitFor(hostC, "init")).isNotNull();

        // Guest sits in the waiting room (never approved) and tries to rename.
        Collector guestC = new Collector();
        WebSocketSession guest = connect(sid, "guest", "Guest", ticketStore.issue(sid, null, "guest"), guestC);
        assertThat(waitFor(guestC, "waitingForApproval")).isNotNull();
        guest.sendMessage(new TextMessage("{\"type\":\"rename\",\"fileName\":\"hijack\"}"));

        assertThat(waitFor(hostC, "rename", 1500)).as("no broadcast from a waiting-room client").isNull();

        host.close(CloseStatus.NORMAL);
        guest.close(CloseStatus.NORMAL);
    }

    @Test
    void renameSanitizesAndRejectsMalformedPayloads() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();

        Collector hostC = new Collector();
        WebSocketSession host = connect(sid, "host", "Host",
                ticketStore.issue(sid, session.getCreatorToken(), "host"), hostC);
        assertThat(waitFor(hostC, "init")).isNotNull();

        // Dot-cut + trim: everything from the first dot goes, then whitespace.
        host.sendMessage(new TextMessage("{\"type\":\"rename\",\"fileName\":\"  my.file.java  \"}"));
        JsonNode echoed = waitFor(hostC, "rename");
        assertThat(echoed).isNotNull();
        assertThat(echoed.path("fileName").asText()).isEqualTo("my");

        // Non-string fileName is dropped without a broadcast.
        host.sendMessage(new TextMessage("{\"type\":\"rename\",\"fileName\":5}"));
        assertThat(waitFor(hostC, "rename", 1500)).as("non-string fileName dropped").isNull();

        host.close(CloseStatus.NORMAL);
    }

    /** Host + approved guest, both admitted. Returns {host, guest} sockets. */
    private WebSocketSession[] hostAndGuest(String sid, String adminToken, Collector hostC, Collector guestC) throws Exception {
        WebSocketSession host = connect(sid, "host", "Host", ticketStore.issue(sid, adminToken, "host"), hostC);
        assertThat(waitFor(hostC, "init")).isNotNull();
        WebSocketSession guest = connect(sid, "guest", "Guest", ticketStore.issue(sid, null, "guest"), guestC);
        JsonNode jr = waitFor(hostC, "joinRequest");
        host.sendMessage(new TextMessage("{\"type\":\"approve\",\"joinKey\":\"" + jr.path("joinKey").asText() + "\"}"));
        assertThat(waitFor(guestC, "init")).isNotNull();
        return new WebSocketSession[] {host, guest};
    }

    @Test
    void hostLeavingOffersAdmin_guestBecomesAdminOnlyAfterAccepting() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();
        Collector hostC = new Collector(), guestC = new Collector();
        WebSocketSession[] ws = hostAndGuest(sid, session.getCreatorToken(), hostC, guestC);

        ws[0].close(CloseStatus.NORMAL);
        assertThat(waitFor(guestC, "adminOffer")).as("guest is offered the seat").isNotNull();
        assertThat(sessionService.isAdmin(sid, session.getCreatorToken())).as("seat parked until accepted").isTrue();

        ws[1].sendMessage(new TextMessage("{\"type\":\"acceptAdmin\"}"));
        JsonNode changed = waitFor(guestC, "adminChanged");
        assertThat(changed).isNotNull();
        assertThat(changed.path("clientId").asText()).isEqualTo("guest");

        ws[1].close(CloseStatus.NORMAL);
    }

    @Test
    void endSessionKeepsTheDurableSnapshotForTheGraceWindow() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();
        String adminToken = session.getCreatorToken();

        Collector hostC = new Collector();
        WebSocketSession host = connect(sid, "host", "Host", ticketStore.issue(sid, adminToken, "host"), hostC);
        assertThat(waitFor(hostC, "init")).isNotNull();
        host.sendMessage(new TextMessage("{\"type\":\"insert\",\"clientId\":\"host\",\"baseVersion\":0,\"pos\":0,\"text\":\"keep\"}"));
        assertThat(waitFor(hostC, "op")).isNotNull();

        host.sendMessage(new TextMessage("{\"type\":\"endsession\"}"));
        assertThat(waitFor(hostC, "sessionEnded")).isNotNull();
        long end = System.currentTimeMillis() + 5000;
        while (otService.hasStore(sid) && System.currentTimeMillis() < end) Thread.sleep(50);

        assertThat(otService.hasStore(sid)).as("evicted from RAM").isFalse();
        assertThat(repository.load(sid)).as("durable snapshot survives end-session")
                .get().extracting(StoredDocument::text).isEqualTo("keep");

        // "Ended" is final: nobody (not even the host, e.g. mid-reconnect) reopens it.
        assertThat(sessionService.isEnded(sid)).isTrue();
        assertThatThrownBy(() -> connect(sid, "host2", "Host", ticketStore.issue(sid, adminToken, "host2"), new Collector()))
                .as("handshake into an ended session is refused").isNotNull();
    }

    @Test
    void catchupReplaysMissedOpsThenCaughtUp() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();
        Collector c = new Collector();
        WebSocketSession host = connect(sid, "host", "Host", ticketStore.issue(sid, session.getCreatorToken(), "host"), c);
        assertThat(waitFor(c, "init")).isNotNull();
        host.sendMessage(new TextMessage("{\"type\":\"insert\",\"baseVersion\":0,\"pos\":0,\"text\":\"a\"}"));
        host.sendMessage(new TextMessage("{\"type\":\"insert\",\"baseVersion\":1,\"pos\":1,\"text\":\"b\"}"));
        long end = System.currentTimeMillis() + 5000;
        while (otService.getVersion(sid) < 2 && System.currentTimeMillis() < end) Thread.sleep(20);

        Collector r = new Collector();
        WebSocketSession again = connect(sid, "host2", "Host", ticketStore.issue(sid, session.getCreatorToken(), "host2"), r);
        assertThat(waitFor(r, "init")).isNotNull();
        again.sendMessage(new TextMessage("{\"type\":\"catchup\",\"from\":0}"));
        List<Integer> versions = new ArrayList<>();
        JsonNode done = null;
        end = System.currentTimeMillis() + 5000;
        while (done == null && System.currentTimeMillis() < end) {
            String msg = r.q.poll(200, TimeUnit.MILLISECONDS);
            if (msg == null) continue;
            JsonNode n = mapper.readTree(msg);
            if ("op".equals(n.path("type").asText())) versions.add(n.path("version").asInt());
            if ("caughtUp".equals(n.path("type").asText())) done = n;
        }
        assertThat(done).isNotNull();
        assertThat(done.path("version").asInt()).isEqualTo(2);
        assertThat(versions).containsExactly(1, 2);
        host.close(CloseStatus.NORMAL);
        again.close(CloseStatus.NORMAL);
    }

    @Test
    void opsAreAttributedToTheSocketAndBadOnesAreNacked() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();
        Collector c = new Collector();
        WebSocketSession host = connect(sid, "host", "Host", ticketStore.issue(sid, session.getCreatorToken(), "host"), c);
        assertThat(waitFor(c, "init")).isNotNull();

        // A claimed clientId is ignored (committed under the socket's id); an unknown field is tolerated.
        host.sendMessage(new TextMessage("{\"type\":\"insert\",\"clientId\":\"victim\",\"baseVersion\":0,\"pos\":0,\"text\":\"x\",\"extra\":1}"));
        JsonNode op = waitFor(c, "op");
        assertThat(op).isNotNull();
        assertThat(op.path("op").path("clientId").asText()).isEqualTo("host");

        // An op that can never apply is nacked and followed by a forced resync.
        host.sendMessage(new TextMessage("{\"type\":\"insert\",\"baseVersion\":99,\"pos\":0,\"text\":\"future\"}"));
        assertThat(waitFor(c, "nack").path("reason").asText()).isEqualTo("invalid");
        assertThat(waitFor(c, "resync").path("required").asBoolean()).isTrue();
        host.close(CloseStatus.NORMAL);
    }

    @Test
    void kickClosesEverySocketOfThatMember() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();
        Collector hostC = new Collector();
        WebSocketSession host = connect(sid, "host", "Host", ticketStore.issue(sid, session.getCreatorToken(), "host"), hostC);
        assertThat(waitFor(hostC, "init")).isNotNull();
        Collector g1 = new Collector();
        connect(sid, "g1", "Guest", ticketStore.issue(sid, null, "g1"), g1);
        String token = waitFor(g1, "waitingForApproval").path("token").asText();
        host.sendMessage(new TextMessage("{\"type\":\"approve\",\"joinKey\":\"" + waitFor(hostC, "joinRequest").path("joinKey").asText() + "\"}"));
        assertThat(waitFor(g1, "init")).isNotNull();
        Collector g2 = new Collector(); // same member, second socket (another tab / a raw socket)
        connect(sid, "g2", "Guest", ticketStore.issue(sid, token, "g2"), g2);
        assertThat(waitFor(g2, "init")).isNotNull();

        host.sendMessage(new TextMessage("{\"type\":\"kick\",\"clientId\":\"g1\"}"));
        assertThat(waitFor(g1, "kicked")).isNotNull();
        assertThat(waitFor(g2, "kicked")).as("the second socket goes too").isNotNull();
        host.close(CloseStatus.NORMAL);
    }

    @Test
    void waiterIsToldWhetherTheHostIsOnline() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();
        // Guest arrives first: the host closed the tab after sharing the link.
        Collector g = new Collector();
        connect(sid, "g", "Guest", ticketStore.issue(sid, null, "g"), g);
        JsonNode waiting = waitFor(g, "waitingForApproval");
        assertThat(waiting).isNotNull();
        assertThat(waiting.path("hostOnline").asBoolean(true)).as("no host socket yet").isFalse();
        // Host comes back: the waiter learns it without reconnecting.
        Collector hostC = new Collector();
        WebSocketSession host = connect(sid, "host", "Host", ticketStore.issue(sid, session.getCreatorToken(), "host"), hostC);
        assertThat(waitFor(hostC, "init")).isNotNull();
        JsonNode status = waitFor(g, "hostStatus");
        assertThat(status).isNotNull();
        assertThat(status.path("online").asBoolean(false)).isTrue();
        // And a guest arriving while the host is present is told so up front.
        Collector g2 = new Collector();
        connect(sid, "g2", "Guest2", ticketStore.issue(sid, null, "g2"), g2);
        assertThat(waitFor(g2, "waitingForApproval").path("hostOnline").asBoolean(false)).isTrue();
        host.close(CloseStatus.NORMAL);
    }

    @Test
    void pingIsAnsweredWithPong() throws Exception {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        String sid = session.getSessionId();
        Collector c = new Collector();
        WebSocketSession host = connect(sid, "host", "Host", ticketStore.issue(sid, session.getCreatorToken(), "host"), c);
        assertThat(waitFor(c, "init")).isNotNull();
        host.sendMessage(new TextMessage("{\"type\":\"ping\"}"));
        assertThat(waitFor(c, "pong")).isNotNull();
        host.close(CloseStatus.NORMAL);
    }

    @Test
    void handshakeWithoutTicketIsRejected() {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        assertThatThrownBy(() ->
                new StandardWebSocketClient()
                        .execute(new Collector(), "ws://localhost:" + port + "/ws/ot?sessionId=" + session.getSessionId() + "&clientId=x")
                        .get(5, TimeUnit.SECONDS))
                .isInstanceOf(Exception.class);
    }
}
