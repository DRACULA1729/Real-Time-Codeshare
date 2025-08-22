package com.collabcode.realtime_codeshare.snapshot;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "snapshots")
@CompoundIndex(name = "session_version_idx", def = "{'sessionId': 1, 'version': -1}")
public class Snapshot {

    @Id
    private String id;

    private String sessionId;
    private int version;
    private String document;
    private String createdBy;        // clientId who clicked save
    private Instant timestamp;       // server time
    private Map<String, Object> metadata; // { language, note, ... }

    public Snapshot() {}

    public Snapshot(String sessionId, int version, String document,
                    String createdBy, Instant timestamp, Map<String, Object> metadata) {
        this.sessionId = sessionId;
        this.version = version;
        this.document = document;
        this.createdBy = createdBy;
        this.timestamp = timestamp;
        this.metadata = metadata;
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public int getVersion() { return version; }
    public String getDocument() { return document; }
    public String getCreatedBy() { return createdBy; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, Object> getMetadata() { return metadata; }

    public void setId(String id) { this.id = id; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setVersion(int version) { this.version = version; }
    public void setDocument(String document) { this.document = document; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
