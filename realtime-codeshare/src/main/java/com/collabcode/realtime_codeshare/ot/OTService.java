package com.collabcode.realtime_codeshare.ot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 */
@Service
public class OTService {

    private static final Logger log = LoggerFactory.getLogger(OTService.class);

    /** Max characters an insert may carry / max span a delete may cover. */
    static final int MAX_OP_LEN = 65_536;

    private final Map<String, DocumentStore> sessions = new ConcurrentHashMap<>();

    public DocumentStore getOrCreateStore(String sessionId) {
        return sessions.computeIfAbsent(sessionId, k -> new DocumentStore());
    }

    public boolean hasStore(String sessionId) {
        return sessions.containsKey(sessionId);
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

    /** Drop a room's entire OT state. Called when a room empties or is reaped. */
    public void evict(String sessionId) {
        if (sessions.remove(sessionId) != null) {
            log.info("Evicted document store for session {}", sessionId);
        }
    }

    public String getDocument(String sessionId) {
        return getOrCreateStore(sessionId).getDocument();
    }

    public int getVersion(String sessionId) {
        return getOrCreateStore(sessionId).getVersion();
    }

    // ---------------- validation ----------------

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
                if (text.length() > MAX_OP_LEN)
                    throw new InvalidOpException("insert text exceeds " + MAX_OP_LEN + " chars");
                return new InsertOp(dto.clientId, dto.baseVersion, dto.pos, text);
            }
            case "delete" -> {
                int length = dto.length == null ? 0 : dto.length;
                if (length < 0) throw new InvalidOpException("delete length must be >= 0");
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

        public synchronized int getVersion() { return version.get(); }

        public synchronized String getDocument() { return doc.toString(); }

        public synchronized int historySize() { return history.size(); }

        /**
         * Transform the incoming op against every op committed since its base
         * version, apply it to the document, bump the version, and append it to
         * history — all under this store's monitor so the
         * version/history/document invariant can never be observed mid-update.
         */
        public synchronized ApplyResult commit(Op incoming) {
            Op transformed = incoming.copy();
            int base = incoming.getBaseVersion();
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
            history.add(new CommittedOp(transformed.copy(), newVersion));

            if (history.size() > MAX_HISTORY) {
                // Drop the oldest ops; clients at the bleeding edge stay correct,
                // very stale clients re-sync via init. Precise snapshotting later.
                compactBelow(newVersion - MAX_HISTORY);
            }
            return new ApplyResult(newVersion, transformed, doc.toString().hashCode());
        }

        /** Atomic (document, version, hash) snapshot for re-sync. */
        public synchronized Snapshot snapshot() {
            String d = doc.toString();
            return new Snapshot(d, version.get(), d.hashCode());
        }

        /**
         * Seam for snapshot-based compaction: discard every committed op at or
         * below {@code version}. Safe only for ops no client will still rebase on.
         */
        public synchronized void compactBelow(int version) {
            history.removeIf(co -> co.version <= version);
        }
    }

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
        public int docHash;

        public ApplyResult() {}

        public ApplyResult(int newVersion, Op transformedOp, int docHash) {
            this.newVersion = newVersion;
            this.transformedOp = transformedOp;
            this.docHash = docHash;
        }

        public int getNewVersion() { return newVersion; }
        public Op getTransformedOp() { return transformedOp; }
        public int getDocHash() { return docHash; }
    }

    /** Immutable (document, version, hash) snapshot of a store. */
    public record Snapshot(String document, int version, int docHash) {}

    public Snapshot snapshot(String sessionId) {
        return getOrCreateStore(sessionId).snapshot();
    }
}
