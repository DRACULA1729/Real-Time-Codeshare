package com.collabcode.realtime_codeshare.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionStateJpaRepository extends JpaRepository<SessionStateEntity, String> {
    List<SessionStateEntity> findByLastActivityMsLessThan(long cutoffMs);
}
