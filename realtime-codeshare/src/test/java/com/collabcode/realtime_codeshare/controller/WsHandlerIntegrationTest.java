package com.collabcode.realtime_codeshare.controller;

import com.collabcode.realtime_codeshare.model.Session;
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

    private final ObjectMapper mapper = new ObjectMapper();

    static class Collector extends TextWebSocketHandler {
        final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        @Override
        public void handleTextMessage(WebSocketSession s, TextMessage m) {
            q.add(m.getPayload());
        }
    }

    private WebSocketSession connect(String sid, String clientId, String name, String ticket, Collector c) throws Exception {
        String url = "ws://localhost:" + port + "/ws/ot?sessionId=" + sid
                + "&clientId=" + clientId + "&name=" + name + "&ticket=" + ticket;
        return new StandardWebSocketClient().execute(c, url).get(5, TimeUnit.SECONDS);
    }

    private JsonNode waitFor(Collector c, String type) throws Exception {
        long end = System.currentTimeMillis() + 5000;
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
    void handshakeWithoutTicketIsRejected() {
        Session session = sessionService.createSession("Host", "#7aa2f7");
        assertThatThrownBy(() ->
                new StandardWebSocketClient()
                        .execute(new Collector(), "ws://localhost:" + port + "/ws/ot?sessionId=" + session.getSessionId() + "&clientId=x")
                        .get(5, TimeUnit.SECONDS))
                .isInstanceOf(Exception.class);
    }
}
