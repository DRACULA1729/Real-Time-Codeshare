package com.collabcode.realtime_codeshare.persistence.redis;

import com.collabcode.realtime_codeshare.persistence.DocumentRepository;
import com.collabcode.realtime_codeshare.persistence.StoredDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Shared/live {@link DocumentRepository} backed by Redis (Upstash-compatible). Active
 * under the {@code redis} profile. Each room is one hash {@code cs:doc:{id}} carrying
 * {text, version}, so any node can rehydrate a room another node last wrote.
 *
 * <p>Each write refreshes a TTL so a room abandoned without an explicit evict
 * self-expires instead of leaking keys (managed-Redis quota). The {@code postgres}
 * and {@code redis} profiles are mutually exclusive in this build (see
 * {@code ScaleProfileGuard}); the composite Redis-over-Postgres design is future work.
 */
@Component
@Profile("redis & !postgres")
public class RedisDocumentRepository implements DocumentRepository {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisDocumentRepository(StringRedisTemplate redis,
                                   @Value("${codeshare.redis.doc-ttl-hours:24}") long ttlHours) {
        this.redis = redis;
        this.ttl = Duration.ofHours(Math.max(1, ttlHours));
    }

    private static String key(String sessionId) {
        return "cs:doc:" + sessionId;
    }

    @Override
    public void save(String sessionId, String text, int version, String name) {
        String k = key(sessionId);
        redis.opsForHash().putAll(k, Map.of(
                "text", text == null ? "" : text,
                "version", Integer.toString(version),
                "name", name == null ? "" : name));
        redis.expire(k, ttl); // refresh on every write; abandoned rooms expire
    }

    @Override
    public Optional<StoredDocument> load(String sessionId) {
        Map<Object, Object> h = redis.opsForHash().entries(key(sessionId));
        if (h.isEmpty()) return Optional.empty();
        String text = String.valueOf(h.getOrDefault("text", ""));
        int version;
        try {
            version = Integer.parseInt(String.valueOf(h.getOrDefault("version", "0")));
        } catch (NumberFormatException e) {
            version = 0;
        }
        String name = String.valueOf(h.getOrDefault("name", ""));
        return Optional.of(new StoredDocument(text, version, name));
    }

    @Override
    public void delete(String sessionId) {
        redis.delete(key(sessionId));
    }
}
