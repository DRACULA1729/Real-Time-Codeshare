package com.collabcode.realtime_codeshare.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** Durable document snapshot row, keyed by session id. {@code updated_at} is set by
 *  Hibernate on every insert and update, so it reflects the last write. */
@Entity
@Table(name = "document_snapshot")
public class DocumentSnapshotEntity {

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "text", columnDefinition = "text", nullable = false)
    private String text;

    @Column(name = "version", nullable = false)
    private int version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentSnapshotEntity() {} // for JPA

    public DocumentSnapshotEntity(String sessionId, String text, int version) {
        this.sessionId = sessionId;
        this.text = text == null ? "" : text;
        this.version = version;
    }

    public String getSessionId() { return sessionId; }
    public String getText() { return text; }
    public int getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
