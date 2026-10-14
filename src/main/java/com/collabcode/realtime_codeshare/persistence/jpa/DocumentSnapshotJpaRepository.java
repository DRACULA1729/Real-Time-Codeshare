package com.collabcode.realtime_codeshare.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for durable document snapshots. Only instantiated
 *  when JPA is auto-configured (the {@code postgres} profile). */
public interface DocumentSnapshotJpaRepository extends JpaRepository<DocumentSnapshotEntity, String> {
}
