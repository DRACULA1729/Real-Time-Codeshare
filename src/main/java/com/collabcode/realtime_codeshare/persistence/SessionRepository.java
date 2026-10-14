package com.collabcode.realtime_codeshare.persistence;

import com.collabcode.realtime_codeshare.model.SessionState;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Durable copy of each room's access-control state, so a restart (deploy) keeps
 * invite links and member tokens valid: clients reconnect into the same room with
 * the same roles instead of landing on "session not found".
 */
public interface SessionRepository {

    /** Upsert the state for a room. */
    void save(SessionState state);

    Optional<SessionState> load(String sessionId);

    void delete(String sessionId);

    /** Delete rows idle since before {@code cutoffMs} that are not in {@code keep}
     *  (the sessions currently resident in memory, which the caller reaps itself).
     *  @return the ids deleted, so their document snapshots can go too. */
    List<String> reapIdle(long cutoffMs, Collection<String> keep);
}
