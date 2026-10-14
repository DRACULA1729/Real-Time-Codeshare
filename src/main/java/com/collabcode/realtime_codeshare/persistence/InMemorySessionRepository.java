package com.collabcode.realtime_codeshare.persistence;

import com.collabcode.realtime_codeshare.model.SessionState;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Default single-process {@link SessionRepository}: no restart durability (the
 *  {@code postgres} profile adds it), but the same code path as production. */
@Component
@Profile("!postgres")
public class InMemorySessionRepository implements SessionRepository {

    private final Map<String, SessionState> rows = new ConcurrentHashMap<>();

    @Override
    public void save(SessionState state) { rows.put(state.sessionId(), state); }

    @Override
    public Optional<SessionState> load(String sessionId) { return Optional.ofNullable(rows.get(sessionId)); }

    @Override
    public void delete(String sessionId) { rows.remove(sessionId); }

    @Override
    public List<String> reapIdle(long cutoffMs, Collection<String> keep) {
        List<String> gone = new ArrayList<>();
        rows.forEach((id, st) -> { if (st.lastActivityMs() < cutoffMs && !keep.contains(id)) gone.add(id); });
        gone.forEach(rows::remove);
        return gone;
    }
}
