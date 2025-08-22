package com.collabcode.realtime_codeshare.snapshot;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/snapshots")
public class SnapshotController {

    private final SnapshotService svc;

    public SnapshotController(SnapshotService svc) {
        this.svc = svc;
    }

    // Save snapshot of current doc for session
    @PostMapping("/save/{sessionId}")
    public ResponseEntity<?> save(@PathVariable String sessionId,
                                  @RequestBody(required = false) Map<String, Object> body) {
        String createdBy = body != null ? Objects.toString(body.get("createdBy"), "unknown") : "unknown";
        Map<String,Object> metadata = body != null && body.containsKey("metadata")
                ? (Map<String,Object>) body.get("metadata")
                : new HashMap<>();

        Snapshot saved = svc.save(sessionId, createdBy, metadata);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "snapshotId", saved.getId(),
                "version", saved.getVersion(),
                "timestamp", saved.getTimestamp()
        ));
    }

    // List snapshots for a session
    @GetMapping("/{sessionId}")
    public ResponseEntity<?> list(@PathVariable String sessionId) {
        return ResponseEntity.ok(svc.list(sessionId));
    }

    // Get one snapshot by id
    @GetMapping("/id/{snapshotId}")
    public ResponseEntity<?> getOne(@PathVariable String snapshotId) {
        return svc.getById(snapshotId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Restore by snapshot id
    @PostMapping("/restore/id/{snapshotId}")
    public ResponseEntity<?> restoreById(@PathVariable String snapshotId) {
        try {
            Snapshot s = svc.restoreById(snapshotId);
            return ResponseEntity.ok(Map.of(
                    "status", "restored",
                    "sessionId", s.getSessionId(),
                    "version", s.getVersion(),
                    "snapshotId", s.getId()
            ));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // Restore by session + version
    @PostMapping("/restore/{sessionId}/{version}")
    public ResponseEntity<?> restoreByVersion(@PathVariable String sessionId, @PathVariable int version) {
        try {
            Snapshot s = svc.restoreByVersion(sessionId, version);
            return ResponseEntity.ok(Map.of(
                    "status", "restored",
                    "sessionId", s.getSessionId(),
                    "version", s.getVersion(),
                    "snapshotId", s.getId()
            ));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // Latest snapshot helper
    @GetMapping("/latest/{sessionId}")
    public ResponseEntity<?> latest(@PathVariable String sessionId) {
        return svc.getLatest(sessionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.ok().build());
    }
}
