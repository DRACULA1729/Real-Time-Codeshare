package com.collabcode.realtime_codeshare.persistence.redis;

import com.collabcode.realtime_codeshare.persistence.DocumentRepository;
import com.collabcode.realtime_codeshare.persistence.StoredDocument;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Shared/live {@link DocumentRepository} backed by Redis (Upstash-compatible). Active
 * under the {@code redis} profile (when {@code postgres} is not also on — postgres wins
 * as the durable store; the production design layers Redis as a live cache in front of
 * Postgres). Each room is one hash {@code cs:doc:{id}} with {text, version}, so any node
 * can rehydrate a room another node last wrote.
 */
@Component
@Profile("redis & !postgres")
public class RedisDocumentRepository implements DocumentRepository {

    private final StringRedisTemplate redis;

    public RedisDocumentRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(String sessionId) {
        return "cs:doc:" + sessionId;
    }

    @Override
    public void save(String sessionId, String text, int version) {
        redis.opsForHash().putAll(key(sessionId), Map.of(
                "text", text == null ? "" : text,
                "version", Integer.toString(version)));
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
        return Optional.of(new StoredDocument(text, version));
    }

    @Override
    public void delete(String sessionId) {
        redis.delete(key(sessionId));
    }
}
