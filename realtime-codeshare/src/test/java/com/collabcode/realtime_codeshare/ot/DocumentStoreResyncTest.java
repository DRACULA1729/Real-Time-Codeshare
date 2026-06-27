package com.collabcode.realtime_codeshare.ot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The compaction floor: an op whose baseVersion is below the oldest version still
 * in history can no longer be transformed correctly, so {@code commit} must signal
 * a forced resync rather than apply a corrupt transform (P0-3).
 */
class DocumentStoreResyncTest {

    private static OTService.InsertOp insert(int baseVersion, int pos, String text) {
        return new OTService.InsertOp("client-A", baseVersion, pos, text);
    }

    @Test
    void seedEstablishesTheFloorAtTheSnapshotVersion() {
        OTService.DocumentStore store = new OTService.DocumentStore();
        store.seed("hello", 10);

        assertThat(store.getDocument()).isEqualTo("hello");
        assertThat(store.getVersion()).isEqualTo(10);

        // An op based before the snapshot version cannot be transformed — force resync.
        assertThatThrownBy(() -> store.commit(insert(5, 0, "X")))
                .isInstanceOf(ResyncRequiredException.class);

        // An op based at the snapshot version is fine.
        assertThatCode(() -> store.commit(insert(10, 0, "X"))).doesNotThrowAnyException();
        assertThat(store.getDocument()).isEqualTo("Xhello");
    }

    @Test
    void compactionRaisesTheFloorAndBelowFloorOpsForceResync() {
        OTService.DocumentStore store = new OTService.DocumentStore();
        store.commit(insert(0, 0, "a")); // v1
        store.commit(insert(1, 1, "b")); // v2
        store.commit(insert(2, 2, "c")); // v3
        assertThat(store.getDocument()).isEqualTo("abc");

        store.compactBelow(2); // discard history <= v2; floor is now 2

        // base 1 < floor 2 -> the ops needed to rebase it are gone.
        assertThatThrownBy(() -> store.commit(insert(1, 0, "Z")))
                .isInstanceOf(ResyncRequiredException.class);

        // base 2 is still rebaseable against the retained v3 op.
        assertThatCode(() -> store.commit(insert(2, 0, "Z"))).doesNotThrowAnyException();
    }

    @Test
    void snapshotDigestIsTheSha256OfTheDocument() {
        OTService.DocumentStore store = new OTService.DocumentStore();
        store.commit(insert(0, 0, "hello"));
        OTService.Snapshot snap = store.snapshot();
        assertThat(snap.document()).isEqualTo("hello");
        assertThat(snap.docHash()).isEqualTo(OTService.sha256Hex("hello"));
    }
}
