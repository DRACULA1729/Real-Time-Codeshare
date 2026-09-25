package com.collabcode.realtime_codeshare.persistence;

import java.util.Optional;

/**
 * Decouples a room's document lifetime from its connected clients. The OT engine
 * persists a snapshot when a room empties (and periodically), and reloads it on a
 * cache miss, so a transient disconnect-reconnect or a process restart no longer
 * serves an empty document.
 *
 * <p>{@link InMemoryDocumentRepository} is the default and gives reconnect-after-empty
 * recovery within a single process. A Redis (live) + Postgres (durable) implementation
 * — which also survives restarts and node failover — swaps in behind this interface
 * without touching {@code OTService}.
 */
public interface DocumentRepository {

    /** Persist (overwrite) the latest snapshot for a room, including its base file name
     *  and chosen language ("" = auto-detect). */
    void save(String sessionId, String text, int version, String name, String language);

    /** Load the last persisted snapshot, if any. */
    Optional<StoredDocument> load(String sessionId);

    /** Forget a room's snapshot (room ended or fully reaped). */
    void delete(String sessionId);
}
