package com.collabcode.realtime_codeshare.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** One room's access-control state as JSON, plus the two columns the reaper needs. */
@Entity
@Table(name = "session_state")
public class SessionStateEntity {

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "state", columnDefinition = "text", nullable = false)
    private String state;

    @Column(name = "last_activity_ms", nullable = false)
    private long lastActivityMs;

    @Column(name = "ended", nullable = false)
    private boolean ended;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SessionStateEntity() {} // for JPA

    public SessionStateEntity(String sessionId, String state, long lastActivityMs, boolean ended) {
        this.sessionId = sessionId;
        this.state = state;
        this.lastActivityMs = lastActivityMs;
        this.ended = ended;
    }

    public String getSessionId() { return sessionId; }
    public String getState() { return state; }
    public long getLastActivityMs() { return lastActivityMs; }
    public boolean isEnded() { return ended; }
}
