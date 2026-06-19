package com.collabcode.realtime_codeshare.ot;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class OTService {

    private final Map<String, DocumentStore> sessions = new ConcurrentHashMap<>();

    public DocumentStore getOrCreateStore(String sessionId) {
        return sessions.computeIfAbsent(sessionId, k -> new DocumentStore());
    }

    public ApplyResult applyOp(String sessionId, OpDTO dto) {
        DocumentStore store = getOrCreateStore(sessionId);
        Op incoming = dto.toOp();
        List<CommittedOp> opsToTransform = store.getOpsSince(incoming.getBaseVersion());
        Op transformed = incoming.copy();
        System.out.println("[OT DEBUG] incoming: "+incoming);
        for (CommittedOp co : opsToTransform) {
            transformed = OTEngine.transform(transformed, co.op);
            System.out.println("[OT DEBUG]  transform against: " + co.op);
            System.out.println("[OT DEBUG]  transformed -> " + transformed);
        }

        int newVersion = store.applyTransformed(transformed);
        return new ApplyResult(newVersion, transformed);
    }

    public String getDocument(String sessionId) {
        return getOrCreateStore(sessionId).getDocument();
    }

    public int getVersion(String sessionId) {
        return getOrCreateStore(sessionId).getVersion();
    }

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
            return "InsertOp[pos=" + pos + ",text='" + text + "',client=" + clientId + ",baseV=" + baseVersion + "]";
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
            return clientA.compareTo(clientB) < 0;
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

        // PATCHED: Handles inserts inside delete range
        private static DeleteOp transformDeleteInsert(DeleteOp a, InsertOp b) {
            if (b.getPos() <= a.getPos()) {
                a.pos += b.getText().length();
            } else if (b.getPos() < a.getPos() + a.getLength()) {
                a.length += b.getText().length();
            }
            return a;
        }

        // PATCHED: More robust overlap handling
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
        private final StringBuilder doc = new StringBuilder();
        private final List<CommittedOp> history = new ArrayList<>();
        private final AtomicInteger version = new AtomicInteger(0);

        public synchronized int getVersion() { return version.get(); }

        public synchronized String getDocument() { return doc.toString(); }

        public synchronized List<CommittedOp> getOpsSince(int fromVersion) {
            List<CommittedOp> res = new ArrayList<>();
            for (CommittedOp co : history) {
                if (co.version > fromVersion) res.add(co);
            }
            return res;
        }

        public synchronized int applyTransformed(Op op) {
            if (op instanceof InsertOp) {
                InsertOp i = (InsertOp) op;
                int p = Math.max(0, Math.min(i.getPos(), doc.length()));
                doc.insert(p, i.getText());
            } else if (op instanceof DeleteOp) {
                DeleteOp d = (DeleteOp) op;
                int p = Math.max(0, Math.min(d.getPos(), doc.length()));
                int end = Math.max(p, Math.min(doc.length(), p + d.getLength()));
                if (end > p) doc.delete(p, end);
            }
            int newVersion = version.incrementAndGet();
            history.add(new CommittedOp(op.copy(), newVersion));
            return newVersion;
        }

        /**
         * Replace the entire document contents and reset history.
         *
         * WARNING: calling this while clients are actively editing may lead to
         * inconsistent client views until clients receive an explicit "init"
         * or refresh message from the server. Restrict this operation to the
         * session creator / admin, or ensure you broadcast an "init" to clients
         * immediately after calling restoreDocument().
         *
         * @param newText the new document contents (may be null -> treated as empty)
         * @param newVersion if >= 0, set document version to this value; if negative, version will be incremented by 1
         */
        public synchronized void replaceDocument(String newText, int newVersion) {
            // replace content
            doc.setLength(0);
            if (newText != null) doc.append(newText);

            // clear history - the server becomes authoritative from this point
            history.clear();

            if (newVersion >= 0) {
                version.set(newVersion);
            } else {
                // bump version to indicate change
                version.incrementAndGet();
            }
        }
        public synchronized void restore(String text, int version) {
            doc.setLength(0);
            doc.append(text != null ? text : "");
            history.clear();
            if (version > 0) {
                this.version.set(version);
            } else {
                this.version.set(0);
            }
        }

        /**
         * Optional helper if you want to set document and also seed a synthetic "initial" committed op.
         * Not used by default, but available if you prefer to keep one history entry.
         */
        public synchronized void replaceDocumentWithInitialCommit(String newText, int newVersion, Op initialOp) {
            replaceDocument(newText, Math.max(newVersion, 0));
            if (initialOp != null) {
                int v = version.incrementAndGet();
                history.add(new CommittedOp(initialOp.copy(), v));
            }
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

        public Op toOp() {
            if ("insert".equalsIgnoreCase(type)) {
                return new InsertOp(clientId, baseVersion, pos, text == null ? "" : text);
            } else {
                return new DeleteOp(clientId, baseVersion, pos, length == null ? 0 : length);
            }
        }
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
    public synchronized void restoreDocument(String sessionId, String text, int version) {
        DocumentStore store = getOrCreateStore(sessionId);
        store.replaceDocument(text, version);
    }

}
