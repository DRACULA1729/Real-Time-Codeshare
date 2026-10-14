package com.collabcode.realtime_codeshare.persistence.jpa;

import com.collabcode.realtime_codeshare.persistence.DocumentRepository;
import com.collabcode.realtime_codeshare.persistence.StoredDocument;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Durable {@link DocumentRepository} backed by PostgreSQL (Neon-compatible). Active
 * under the {@code postgres} profile. Snapshots survive process restarts and node
 * failures, so a room reloads its real document on cold start (durable half of P0-2).
 *
 * <p>{@code save} is an upsert: the session id is the assigned primary key, so
 * {@code JpaRepository.save} merges (insert-or-update).
 */
@Component
@Profile("postgres")
public class JpaDocumentRepository implements DocumentRepository {

    private final DocumentSnapshotJpaRepository repo;

    public JpaDocumentRepository(DocumentSnapshotJpaRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void save(String sessionId, String text, int version, String name, String language) {
        repo.save(new DocumentSnapshotEntity(sessionId, text, version, name, language));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredDocument> load(String sessionId) {
        return repo.findById(sessionId)
                .map(e -> new StoredDocument(e.getText(), e.getVersion(), e.getFileName(), e.getLanguage()));
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        repo.deleteById(sessionId);
    }
}
