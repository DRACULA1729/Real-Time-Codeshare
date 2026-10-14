package com.collabcode.realtime_codeshare.ot;

import com.collabcode.realtime_codeshare.persistence.DocumentRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Authoritative in-memory Operational-Transform engine.
 *
 * One {@link DocumentStore} per session id holds the document text, a monotonic
 * version counter, and a bounded op history. The transform-and-apply step is a
 * single critical section inside {@link DocumentStore#commit(Op)} so concurrent
 * edits can never observe a half-applied state.
 *
 * <p>A {@link DocumentRepository} decouples document lifetime from connected
 * clients: stores are seeded from a persisted snapshot on a cache miss and
 * snapshotted when a room empties, so a transient disconnect or a restart no
 * longer serves an empty document.
 */
@Service
public class OTService {

    private static final Logger log = LoggerFactory.getLogger(OTService.class);

    /** Max characters an insert may carry / max span a delete may cover. */
    static final int MAX_OP_LEN = 65_536;

    /** Max length of the shared base file name (matches the file_name VARCHAR(64) column). */
    public static final int MAX_BASENAME_LEN = 64;

    private final Map<String, DocumentStore> sessions = new ConcurrentHashMap<>();
    private final DocumentRepository repository;

    public OTService(DocumentRepository repository) {
        this.repository = repository;
    }

    public DocumentStore getOrCreateStore(String sessionId) {
        return sessions.computeIfAbsent(sessionId, k -> {
            DocumentStore store = new DocumentStore();
            // Load-on-miss: a reconnect after the room emptied (or a restart with a
            // durable repository) rehydrates the real document instead of a blank one.
            repository.load(k).ifPresent(snap -> store.seed(snap.text(), snap.version(), snap.name(), snap.language()));
            return store;
        });
    }

    public boolean hasStore(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /** Number of resident document stores (for the live-rooms metric). */
    public int roomCount() {
        return sessions.size();
    }

    /** Persist the current snapshot of a resident room (on-empty / periodic save). */
    public void persist(String sessionId) {
        DocumentStore store = sessions.get(sessionId);
        if (store == null) return;
        Snapshot snap = store.snapshot();
        repository.save(sessionId, snap.document(), snap.version(), store.getName(), store.getLanguage());
    }

    /** Save only rooms changed since their last save — the short-interval write-through
     *  that keeps the crash-loss window to a few seconds without per-op writes. */
    public void persistDirty() {
        sessions.forEach((id, store) -> {
            Snapshot snap = store.takeDirtySnapshot();
            if (snap == null) return;
            try {
                repository.save(id, snap.document(), snap.version(), store.getName(), store.getLanguage());
            } catch (RuntimeException e) {
                store.markDirty();                 // retry on the next sweep
                log.warn("Dirty persist failed for session {}: {}", id, e.toString());
            }
        });
    }

    /** Snapshot every resident room — periodic backstop so a crash loses at most one interval. */
    public void persistAll() {
        sessions.forEach((id, store) -> {
            Snapshot snap = store.snapshot();
            repository.save(id, snap.document(), snap.version(), store.getName(), store.getLanguage());
        });
    }

    /**
     * Validate the incoming DTO, then atomically transform-and-apply it.
     *
     * @throws InvalidOpException if the op is malformed; the caller is expected
     *                            to warn and ignore rather than crash.
     */
    public ApplyResult applyOp(String sessionId, OpDTO dto) {
        Op incoming = validateAndBuild(dto);
        ApplyResult result = getOrCreateStore(sessionId).commit(incoming);
        // DEBUG only: the op carries document content, never log above DEBUG.
        log.debug("[{}] committed {} -> v{}", sessionId, result.getTransformedOp(), result.getNewVersion());
        return result;
    }

    /** Persist and drop a room's resident store but keep its durable snapshot (the
     *  end-session guard): a rejoin rehydrates it, and only the idle reap's
     *  {@link #evict} deletes it. Removed first so every committed op is in the save. */
    public void unload(String sessionId) {
        DocumentStore store = sessions.remove(sessionId);
        if (store == null) return;
        Snapshot snap = store.snapshot();
        repository.save(sessionId, snap.document(), snap.version(), store.getName(), store.getLanguage());
    }

    /** Drop a room's entire OT state, including its persisted snapshot. Called only
     *  when a room is fully reaped after the idle TTL. */
    public void evict(String sessionId) {
        if (sessions.remove(sessionId) != null) {
            log.info("Evicted document store for session {}", sessionId);
        }
        repository.delete(sessionId);
    }

    /** Committed ops after {@code fromVersion}, oldest first, so a reconnecting client
     *  can replay what it missed through its normal OT loop. Null when that range is
     *  no longer (or not yet) available — the caller falls back to a forced resync. */
    public List<CommittedOp> opsSince(String sessionId, int fromVersion) {
        return getOrCreateStore(sessionId).opsSince(fromVersion);
    }

    public String getDocument(String sessionId) {
        return getOrCreateStore(sessionId).getDocument();
    }

    public int getVersion(String sessionId) {
        return getOrCreateStore(sessionId).getVersion();
    }

    /** Shared base file name of the room's document ("" when never renamed). */
    public String getName(String sessionId) {
        return getOrCreateStore(sessionId).getName();
    }

    public void setName(String sessionId, String name) {
        getOrCreateStore(sessionId).setName(name);
    }

    /** Language id chosen for the room's document ("" = each client auto-detects). */
    public String getLanguage(String sessionId) {
        return getOrCreateStore(sessionId).getLanguage();
    }

    public void setLanguage(String sessionId, String language) {
        getOrCreateStore(sessionId).setLanguage(language);
    }

    /** Max length of a language id (matches the language VARCHAR(32) column). */
    public static final int MAX_LANGUAGE_LEN = 32;

    /**
     * Normalize a client-chosen language id. The server doesn't know the client's
     * language table, so it only enforces a safe shape: lowercase letters, digits and
     * {@code + # -}, capped at {@value #MAX_LANGUAGE_LEN}. Anything else becomes ""
     * (auto-detect).
     */
    public static String sanitizeLanguage(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.length() <= MAX_LANGUAGE_LEN && s.matches("[a-z0-9+#-]*") ? s : "";
    }

    // ---------------- validation ----------------

    /**
     * Normalize a user-supplied base file name: cut at the first dot (the extension
     * is derived from language detection, never user-set), strip control chars and
     * path separators, trim, cap at {@value #MAX_BASENAME_LEN} chars. May return ""
     * (displayed as the "untitled" fallback). Must match the client-side
     * sanitizeBaseName in frontend/src/lib/fileName.ts.
     */
    public static String sanitizeBaseName(String raw) {
        if (raw == null) return "";
        String s = raw;
        int dot = s.indexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        s = s.replaceAll("[\\u0000-\\u001F\\u007F/\\\\]", "").trim();
        if (s.length() > MAX_BASENAME_LEN) s = s.substring(0, MAX_BASENAME_LEN);
        return s;
    }

    private Op validateAndBuild(OpDTO dto) {
        if (dto == null) throw new InvalidOpException("op is null");
        if (dto.clientId == null || dto.clientId.isBlank())
            throw new InvalidOpException("clientId must be non-blank");
        if (dto.type == null) throw new InvalidOpException("type is required");
        if (dto.baseVersion < 0) throw new InvalidOpException("baseVersion must be >= 0");
        if (dto.pos < 0) throw new InvalidOpException("pos must be >= 0");

        String type = dto.type.toLowerCase(Locale.ROOT);
        switch (type) {
            case "insert" -> {
                String text = dto.text == null ? "" : dto.text;
                // No-op inserts would still bump the version (and push others past the
                // compaction floor); CR would desync CodeMirror, which stores only LF.
                if (text.isEmpty()) throw new InvalidOpException("insert text must not be empty");
                if (text.indexOf('\r') >= 0) throw new InvalidOpException("insert text must use \\n line breaks, not \\r");
                if (text.length() > MAX_OP_LEN)
                    throw new InvalidOpException("insert text exceeds " + MAX_OP_LEN + " chars");
                return new InsertOp(dto.clientId, dto.baseVersion, dto.pos, text);
            }
            case "delete" -> {
                int length = dto.length == null ? 0 : dto.length;
                if (length <= 0) throw new InvalidOpException("delete length must be > 0");
                if (length > MAX_OP_LEN)
                    throw new InvalidOpException("delete length exceeds " + MAX_OP_LEN);
                return new DeleteOp(dto.clientId, dto.baseVersion, dto.pos, length);
            }
            default -> throw new InvalidOpException("unknown op type: " + dto.type);
        }
    }

    // ---------------- model ----------------

    public static abstract class Op {
        public enum Type { INSERT, DELETE }

        protected String clientId;
        protected int baseVersion;
        public Type type;

        public Op() {}

        protected Op(String clientId, int baseVersion, Type type) {
            this.clientId = clientId;
            this.baseVersion = baseVersion;
            this.type = type;
        }

        public String getClientId() { return clientId; }
        public int getBaseVersion() { return baseVersion; }
        public Type getType() { return type; }

        public abstract Op copy();
    }

    public static class InsertOp extends Op {
        private int pos;
        private String text;

        public InsertOp() { super(); }

        public InsertOp(String clientId, int baseVersion, int pos, String text) {
            super(clientId, baseVersion, Type.INSERT);
            this.pos = pos;
            this.text = text;
        }

        public int getPos() { return pos; }
        public String getText() { return text; }

        @Override
        public InsertOp copy() {
            return new InsertOp(clientId, baseVersion, pos, text);
        }

        @Override
        public String toString() {
            return "InsertOp[pos=" + pos + ",len=" + (text == null ? 0 : text.length())
                    + ",client=" + clientId + ",baseV=" + baseVersion + "]";
        }
    }

    public static class DeleteOp extends Op {
        private int pos;
        private int length;

        public DeleteOp() { super(); }

        public DeleteOp(String clientId, int baseVersion, int pos, int length) {
            super(clientId, baseVersion, Type.DELETE);
            this.pos = pos;
            this.length = length;
        }

        public int getPos() { return pos; }
        public int getLength() { return length; }

        @Override
        public DeleteOp copy() {
            return new DeleteOp(clientId, baseVersion, pos, length);
        }

        @Override
        public String toString() {
            return "DeleteOp[pos=" + pos + ",len=" + length + ",client=" + clientId + ",baseV=" + baseVersion + "]";
        }
    }

    public static class CommittedOp {
        public final Op op;
        public final int version;

        public CommittedOp(Op op, int version) {
            this.op = op;
            this.version = version;
        }

        public Op getOp() { return op; }
        public int getVersion() { return version; }
    }

    public static class OTEngine {

        private static boolean clientWinsInsertTie(String clientA, String clientB) {
            String a = clientA == null ? "" : clientA;
            String b = clientB == null ? "" : clientB;
            return a.compareTo(b) < 0;
        }

        public static Op transform(Op a, Op b) {
            if (a.getType() == Op.Type.INSERT && b.getType() == Op.Type.INSERT) {
                return transformInsertInsert((InsertOp) a.copy(), (InsertOp) b);
            } else if (a.getType() == Op.Type.INSERT && b.getType() == Op.Type.DELETE) {
                return transformInsertDelete((InsertOp) a.copy(), (DeleteOp) b);
            } else if (a.getType() == Op.Type.DELETE && b.getType() == Op.Type.INSERT) {
                return transformDeleteInsert((DeleteOp) a.copy(), (InsertOp) b);
            } else {
                return transformDeleteDelete((DeleteOp) a.copy(), (DeleteOp) b);
            }
        }

        private static InsertOp transformInsertInsert(InsertOp a, InsertOp b) {
            if (a.getPos() < b.getPos()) {
                // no change
            } else if (a.getPos() > b.getPos()) {
                a.pos += b.getText().length();
            } else { // equal
                if (!clientWinsInsertTie(a.getClientId(), b.getClientId())) {
                    a.pos += b.getText().length();
                }
            }
            return a;
        }

        private static InsertOp transformInsertDelete(InsertOp a, DeleteOp b) {
            if (a.getPos() <= b.getPos()) {
                // no change
            } else if (a.getPos() > b.getPos() + b.getLength()) {
                a.pos -= b.getLength();
            } else {
                a.pos = b.getPos();
            }
            return a;
        }

        // Handles inserts inside a delete range.
        private static DeleteOp transformDeleteInsert(DeleteOp a, InsertOp b) {
            if (b.getPos() <= a.getPos()) {
                a.pos += b.getText().length();
            } else if (b.getPos() < a.getPos() + a.getLength()) {
                a.length += b.getText().length();
            }
            return a;
        }

        // Robust overlap handling for two concurrent deletes.
        private static DeleteOp transformDeleteDelete(DeleteOp a, DeleteOp b) {
            int aStart = a.getPos();
            int aEnd = a.getPos() + a.getLength();
            int bStart = b.getPos();
            int bEnd = b.getPos() + b.getLength();

            if (aEnd <= bStart) {
                // no change
            } else if (aStart >= bEnd) {
                a.pos -= b.getLength();
            } else {
                int overlapStart = Math.max(aStart, bStart);
                int overlapEnd = Math.min(aEnd, bEnd);
                int overlap = Math.max(0, overlapEnd - overlapStart);

                a.length -= overlap;
                if (aStart >= bStart) {
                    a.pos -= Math.min(bEnd, aStart) - bStart;
                }
            }
            if (a.length < 0) a.length = 0;
            return a;
        }
    }

    public static class DocumentStore {
        /** Cap on retained history; beyond this the oldest committed ops are compacted away. */
        static final int MAX_HISTORY = 2000;

        private final StringBuilder doc = new StringBuilder();
        private final List<CommittedOp> history = new ArrayList<>();
        private final AtomicInteger version = new AtomicInteger(0);
        /** Highest version whose op has been compacted away; an op based at or below
         *  this can no longer be transformed correctly and must force a re-sync. */
        private int compactedThroughVersion = 0;
        /** Shared base file name; display metadata, last-write-wins across clients. */
        private String name = "";
        /** Changed since the last {@link #takeDirtySnapshot()}. */
        private boolean dirty = false;
        /** Chosen language id ("" = auto-detect); last-write-wins like the name. */
        private String language = "";

        public synchronized int getVersion() { return version.get(); }

        public synchronized String getDocument() { return doc.toString(); }

        public synchronized int historySize() { return history.size(); }

        public synchronized String getName() { return name; }

        public synchronized void setName(String name) { this.name = name == null ? "" : name; dirty = true; }

        public synchronized void markDirty() { dirty = true; }

        /** Snapshot and clear the dirty flag atomically, or null if nothing changed. */
        public synchronized Snapshot takeDirtySnapshot() {
            if (!dirty) return null;
            dirty = false;
            return snapshot();
        }

        public synchronized String getLanguage() { return language; }

        public synchronized void setLanguage(String language) { this.language = language == null ? "" : language; dirty = true; }

        /** Rehydrate a fresh store from a persisted snapshot. History starts empty,
         *  so any op based before this version is treated as below the floor. */
        public synchronized void seed(String text, int atVersion, String name, String language) {
            doc.setLength(0);
            doc.append(text == null ? "" : text);
            version.set(atVersion);
            history.clear();
            compactedThroughVersion = atVersion;
            this.name = name == null ? "" : name;
            this.language = language == null ? "" : language;
        }

        /**
         * Transform the incoming op against every op committed since its base
         * version, apply it to the document, bump the version, and append it to
         * history — all under this store's monitor so the
         * version/history/document invariant can never be observed mid-update.
         *
         * @throws ResyncRequiredException if the op's base version is below the
         *         compaction floor, where the history needed to transform it is gone.
         */
        public synchronized ApplyResult commit(Op incoming) {
            int base = incoming.getBaseVersion();
            if (base > version.get()) {
                // A client can't have built on a version that doesn't exist yet.
                throw new InvalidOpException("baseVersion " + base + " is ahead of the document (v" + version.get() + ")");
            }
            if (base < compactedThroughVersion) {
                throw new ResyncRequiredException(
                        "baseVersion " + base + " below compaction floor " + compactedThroughVersion);
            }
            Op transformed = incoming.copy();
            for (CommittedOp co : history) {
                if (co.version > base) {
                    transformed = OTEngine.transform(transformed, co.op);
                }
            }

            if (transformed instanceof InsertOp i) {
                int p = Math.max(0, Math.min(i.getPos(), doc.length()));
                doc.insert(p, i.getText());
            } else if (transformed instanceof DeleteOp d) {
                int p = Math.max(0, Math.min(d.getPos(), doc.length()));
                int end = Math.max(p, Math.min(doc.length(), p + d.getLength()));
                if (end > p) doc.delete(p, end);
            }

            int newVersion = version.incrementAndGet();
            dirty = true;
            history.add(new CommittedOp(transformed.copy(), newVersion));

            if (history.size() > MAX_HISTORY) {
                // Drop the oldest ops; clients at the bleeding edge stay correct,
                // ones below the new floor are forced to re-sync (see commit guard).
                compactBelow(newVersion - MAX_HISTORY);
            }
            return new ApplyResult(newVersion, transformed);
        }

        /** Ops committed after {@code from}, or null if {@code from} is below the
         *  compaction floor (history gone) or ahead of the current version. */
        public synchronized List<CommittedOp> opsSince(int from) {
            if (from < compactedThroughVersion || from > version.get()) return null;
            List<CommittedOp> out = new ArrayList<>();
            for (CommittedOp co : history) if (co.version > from) out.add(co);
            return out;
        }

        /** Atomic (document, version, SHA-256 digest) snapshot for re-sync. The
         *  digest is computed here (on the quiescent probe / resync exchange) rather
         *  than on every op, so its cost is bounded. */
        public synchronized Snapshot snapshot() {
            String d = doc.toString();
            return new Snapshot(d, version.get(), sha256Hex(d));
        }

        /**
         * Discard every committed op at or below {@code version} and raise the
         * compaction floor accordingly.
         */
        public synchronized void compactBelow(int version) {
            history.removeIf(co -> co.version <= version);
            if (version > compactedThroughVersion) compactedThroughVersion = version;
        }
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes — identical on the Java and TS sides. */
    public static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true) // an extra field must not silently drop an edit
    public static class OpDTO {
        public String clientId;
        public String type;
        public int baseVersion;
        public int pos;
        public String text;
        public Integer length;

        public OpDTO() {}
    }

    public static class ApplyResult {
        public int newVersion;
        public Op transformedOp;

        public ApplyResult() {}

        public ApplyResult(int newVersion, Op transformedOp) {
            this.newVersion = newVersion;
            this.transformedOp = transformedOp;
        }

        public int getNewVersion() { return newVersion; }
        public Op getTransformedOp() { return transformedOp; }
    }

    /** Immutable (document, version, SHA-256 hex digest) snapshot of a store. */
    public record Snapshot(String document, int version, String docHash) {}

    public Snapshot snapshot(String sessionId) {
        return getOrCreateStore(sessionId).snapshot();
    }
}
