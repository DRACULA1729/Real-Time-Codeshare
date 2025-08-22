package com.collabcode.realtime_codeshare.snapshot;

import com.collabcode.realtime_codeshare.controller.OTWebSocketHandler;
import com.collabcode.realtime_codeshare.ot.OTService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.*;

@Service
public class SnapshotService {

    private final SnapshotRepository repo;
    private final OTService otService;
    private final OTWebSocketHandler wsHandler;
    private final ObjectMapper mapper = new ObjectMapper();

    public SnapshotService(SnapshotRepository repo, OTService otService, OTWebSocketHandler wsHandler) {
        this.repo = repo;
        this.otService = otService;
        this.wsHandler = wsHandler;
    }

    public Snapshot save(String sessionId, String createdBy, Map<String, Object> metadata) {
        String doc = otService.getDocument(sessionId);
        int version = otService.getVersion(sessionId);
        Snapshot s = new Snapshot(sessionId, version, doc, createdBy, Instant.now(), metadata);
        return repo.save(s);
    }

    public List<Snapshot> list(String sessionId) {
        return repo.findBySessionIdOrderByVersionDesc(sessionId);
    }

    public Optional<Snapshot> getById(String id) {
        return repo.findById(id);
    }

    public Optional<Snapshot> getLatest(String sessionId) {
        return repo.findTopBySessionIdOrderByVersionDesc(sessionId);
    }

    public Snapshot restoreById(String snapshotId) throws Exception {
        Snapshot s = repo.findById(snapshotId).orElseThrow(() -> new NoSuchElementException("snapshot not found"));
        otService.restoreDocument(s.getSessionId(), s.getDocument(), s.getVersion());

        // broadcast fresh init to session
        broadcastInitToSession(s.getSessionId());
        return s;
    }

    public Snapshot restoreByVersion(String sessionId, int version) throws Exception {
        Snapshot s = repo.findBySessionIdAndVersion(sessionId, version)
                .orElseThrow(() -> new NoSuchElementException("snapshot not found"));
        otService.restoreDocument(s.getSessionId(), s.getDocument(), s.getVersion());
        broadcastInitToSession(s.getSessionId());
        return s;
    }

    // -------- helpers -------- //

    private void broadcastInitToSession(String sessionId) throws Exception {
        String currentDoc = otService.getDocument(sessionId);
        int currentVersion = otService.getVersion(sessionId);

        Map<String, Object> init = Map.of(
                "type", "init",
                "document", currentDoc,
                "version", currentVersion
        );
        String json = mapper.writeValueAsString(init);

        for (WebSocketSession s : wsHandler.getRoomClients().getOrDefault(sessionId, Collections.emptySet())) {
            if (s.isOpen()) s.sendMessage(new TextMessage(json));
        }
    }
}
