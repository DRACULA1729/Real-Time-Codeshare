package com.collabcode.realtime_codeshare.persistence;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default single-node {@link DocumentRepository}. Snapshots live in the same JVM,
 * so this gives reconnect-after-empty recovery but not restart durability — that
 * arrives with the Redis/Postgres implementation in the scale phase.
 */
@Component
public class InMemoryDocumentRepository implements DocumentRepository {

    private final Map<String, StoredDocument> docs = new ConcurrentHashMap<>();

    @Override
    public void save(String sessionId, String text, int version) {
        docs.put(sessionId, new StoredDocument(text, version));
    }

    @Override
    public Optional<StoredDocument> load(String sessionId) {
        return Optional.ofNullable(docs.get(sessionId));
    }

    @Override
    public void delete(String sessionId) {
        docs.remove(sessionId);
    }
}
