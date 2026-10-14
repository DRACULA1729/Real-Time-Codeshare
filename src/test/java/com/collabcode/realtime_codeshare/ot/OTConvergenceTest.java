package com.collabcode.realtime_codeshare.ot;

import com.collabcode.realtime_codeshare.ot.OTService.DeleteOp;
import com.collabcode.realtime_codeshare.ot.OTService.DocumentStore;
import com.collabcode.realtime_codeshare.ot.OTService.InsertOp;
import com.collabcode.realtime_codeshare.ot.OTService.Op;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent ops (sharing a base version) must converge to the same document on
 * every server regardless of the order in which they arrive — the defining
 * property of the OT engine.
 */
class OTConvergenceTest {

    /** Commit the seed in order, then the concurrent ops in {@code order}; return the final doc. */
    private static String run(List<Op> seed, List<Op> order) {
        DocumentStore store = new DocumentStore();
        for (Op o : seed) store.commit(o);
        for (Op o : order) store.commit(o);
        return store.getDocument();
    }

    /** Assert every permutation of {@code concurrent} yields the same final document. */
    private static String assertConverges(List<Op> seed, List<Op> concurrent) {
        List<List<Op>> perms = permutations(concurrent);
        String expected = run(seed, perms.get(0));
        for (List<Op> perm : perms) {
            assertEquals(expected, run(seed, perm),
                    "ordering " + perm + " diverged");
        }
        return expected;
    }

    private static List<List<Op>> permutations(List<Op> items) {
        List<List<Op>> out = new ArrayList<>();
        permute(items, new ArrayList<>(), new boolean[items.size()], out);
        return out;
    }

    private static void permute(List<Op> items, List<Op> cur, boolean[] used, List<List<Op>> out) {
        if (cur.size() == items.size()) {
            out.add(new ArrayList<>(cur));
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            if (used[i]) continue;
            used[i] = true;
            cur.add(items.get(i));
            permute(items, cur, used, out);
            cur.remove(cur.size() - 1);
            used[i] = false;
        }
    }

    @Test
    void twoConcurrentInserts_atSamePosition() {
        // "AAA" by client a and "BBB" by client b, both at pos 0, base v0.
        // Tie-break by clientId (a < b) => a's text lands first.
        String doc = assertConverges(List.of(), List.of(
                new InsertOp("a", 0, 0, "AAA"),
                new InsertOp("b", 0, 0, "BBB")
        ));
        assertEquals("AAABBB", doc);
    }

    @Test
    void threeConcurrentInserts_atSamePosition() {
        String doc = assertConverges(List.of(), List.of(
                new InsertOp("x", 0, 0, "x"),
                new InsertOp("y", 0, 0, "y"),
                new InsertOp("z", 0, 0, "z")
        ));
        assertEquals("xyz", doc);
    }

    @Test
    void concurrentInsertAndDelete_overSeededDoc() {
        // Seed "HELLO" at v1, then concurrent ops at base v1:
        //   client a deletes the leading "H"; client b appends "!".
        List<Op> seed = List.of(new InsertOp("seed", 0, 0, "HELLO"));
        String doc = assertConverges(seed, List.of(
                new DeleteOp("a", 1, 0, 1),
                new InsertOp("b", 1, 5, "!")
        ));
        assertEquals("ELLO!", doc);
    }

    @Test
    void concurrentOverlappingDeletes() {
        // Seed "ABCDEF"; a deletes [1,4) "BCD", b deletes [3,6) "DEF".
        List<Op> seed = List.of(new InsertOp("seed", 0, 0, "ABCDEF"));
        String doc = assertConverges(seed, List.of(
                new DeleteOp("a", 1, 1, 3),
                new DeleteOp("b", 1, 3, 3)
        ));
        // Union of deletions [1,6) removed => "A".
        assertEquals("A", doc);
    }
}
