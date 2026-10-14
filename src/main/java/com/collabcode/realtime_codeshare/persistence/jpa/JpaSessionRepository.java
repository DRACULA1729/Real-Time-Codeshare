package com.collabcode.realtime_codeshare.persistence.jpa;

import com.collabcode.realtime_codeshare.model.SessionState;
import com.collabcode.realtime_codeshare.persistence.SessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Postgres-backed {@link SessionRepository}: rooms survive a restart. */
@Component
@Profile("postgres")
public class JpaSessionRepository implements SessionRepository {

    private final SessionStateJpaRepository repo;
    private final ObjectMapper mapper;

    public JpaSessionRepository(SessionStateJpaRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(SessionState state) {
        try {
            repo.save(new SessionStateEntity(state.sessionId(), mapper.writeValueAsString(state),
                    state.lastActivityMs(), state.ended()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize session " + state.sessionId(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionState> load(String sessionId) {
        return repo.findById(sessionId).map(e -> {
            try {
                return mapper.readValue(e.getState(), SessionState.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("corrupt session row " + sessionId, ex);
            }
        });
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        repo.deleteById(sessionId);
    }

    @Override
    @Transactional
    public List<String> reapIdle(long cutoffMs, Collection<String> keep) {
        List<SessionStateEntity> stale = repo.findByLastActivityMsLessThan(cutoffMs);
        stale.removeIf(e -> keep.contains(e.getSessionId()));
        List<String> ids = new ArrayList<>();
        stale.forEach(e -> ids.add(e.getSessionId()));
        repo.deleteAll(stale);
        return ids;
    }
}
