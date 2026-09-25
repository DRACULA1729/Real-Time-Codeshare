package com.collabcode.realtime_codeshare.persistence;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default single-node {@link DocumentRepository}. Snapshots live in the same JVM,
 * so this gives reconnect-after-empty recovery but not restart durability — that
 * arrives with the Postgres/Redis implementations under the scale profiles. Active
 * only when neither the {@code postgres} nor {@code redis} profile is on.
 */
@Component
@Profile("!postgres & !redis")
public class InMemoryDocumentRepository implements DocumentRepository {

    private final Map<String, StoredDocument> docs = new ConcurrentHashMap<>();

    public InMemoryDocumentRepository() {
        LoggerFactory.getLogger(InMemoryDocumentRepository.class).warn(
                "Documents are held in memory only and will be LOST on restart. "
                + "Activate the 'postgres' profile for restart durability.");
    }

    @Override
    public void save(String sessionId, String text, int version, String name) {
        docs.put(sessionId, new StoredDocument(text, version, name));
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
