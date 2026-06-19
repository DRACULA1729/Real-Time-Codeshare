package com.collabcode.realtime_codeshare.ot;

import com.collabcode.realtime_codeshare.ot.OTService.DeleteOp;
import com.collabcode.realtime_codeshare.ot.OTService.DocumentStore;
import com.collabcode.realtime_codeshare.ot.OTService.InsertOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the atomic commit path: transform-against-history, apply, version, and bounded history. */
class DocumentStoreCommitTest {

    @Test
    void insertThenDelete_updatesDocAndVersion() {
        DocumentStore store = new DocumentStore();
        store.commit(new InsertOp("a", 0, 0, "Hello"));
        assertEquals("Hello", store.getDocument());
        assertEquals(1, store.getVersion());

        store.commit(new DeleteOp("a", 1, 0, 1)); // drop "H"
        assertEquals("ello", store.getDocument());
        assertEquals(2, store.getVersion());
    }

    @Test
    void clampsOutOfRangePositions() {
        DocumentStore store = new DocumentStore();
        store.commit(new InsertOp("a", 0, 999, "hi")); // pos past end -> clamps to end
        assertEquals("hi", store.getDocument());

        store.commit(new DeleteOp("a", 1, 0, 999));     // over-long delete -> clamps
        assertEquals("", store.getDocument());
    }

    /**
     * Regression for the non-atomic applyOp bug: an op submitted against a stale
     * base version must be transformed against everything committed since, not
     * applied at its raw coordinates.
     */
    @Test
    void staleBaseVersion_isTransformedAgainstNewerHistory() {
        DocumentStore store = new DocumentStore();
        store.commit(new InsertOp("a", 0, 0, "Hello")); // v1, doc "Hello"

        // Client b never saw v1 (baseVersion 0) and inserts "!" at end-of-its-world (pos 5).
        // Against the now-committed "Hello" insert at pos 0, b's position must shift right.
        var result = store.commit(new InsertOp("b", 0, 5, "!"));
        assertEquals(2, result.getNewVersion());
        // Had the transform been skipped, "!" would have landed inside "Hello".
        assertEquals("Hello!", store.getDocument());
    }

    @Test
    void historyIsBounded_andDocStaysCorrect() {
        DocumentStore store = new DocumentStore();
        int n = DocumentStore.MAX_HISTORY + 100;
        for (int i = 0; i < n; i++) {
            store.commit(new InsertOp("a", store.getVersion(), store.getDocument().length(), "x"));
        }
        assertEquals(n, store.getVersion());
        assertEquals(n, store.getDocument().length());
        assertTrue(store.historySize() <= DocumentStore.MAX_HISTORY,
                "history should be capped at " + DocumentStore.MAX_HISTORY);
    }

    @Test
    void compactBelow_dropsOldHistory() {
        DocumentStore store = new DocumentStore();
        store.commit(new InsertOp("a", 0, 0, "a"));
        store.commit(new InsertOp("a", 1, 1, "b"));
        store.commit(new InsertOp("a", 2, 2, "c"));
        store.compactBelow(store.getVersion());
        assertEquals(0, store.historySize());
        // Document and version are untouched by compaction.
        assertEquals("abc", store.getDocument());
        assertEquals(3, store.getVersion());
    }
}
