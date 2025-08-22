package com.collabcode.realtime_codeshare.snapshot;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SnapshotRepository extends MongoRepository<Snapshot, String> {
    List<Snapshot> findBySessionIdOrderByVersionDesc(String sessionId);
    Optional<Snapshot> findTopBySessionIdOrderByVersionDesc(String sessionId);
    Optional<Snapshot> findBySessionIdAndVersion(String sessionId, int version);
}

