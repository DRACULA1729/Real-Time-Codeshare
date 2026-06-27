package com.collabcode.realtime_codeshare.persistence;

import com.collabcode.realtime_codeshare.ot.OTService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Document lifetime is decoupled from connected clients (P0-2): a room snapshot is
 * persisted on demand, reloaded on a cache miss (the reconnect-after-empty path),
 * and only the full eviction removes the durable copy.
 */
class DocumentPersistenceTest {

    private static OTService.OpDTO insert(int pos, String text) {
        OTService.OpDTO dto = new OTService.OpDTO();
        dto.clientId = "client-A";
        dto.type = "insert";
        dto.baseVersion = 0;
        dto.pos = pos;
        dto.text = text;
        return dto;
    }

    @Test
    void persistThenReloadOnMissRestoresTheDocument() {
        DocumentRepository repo = new InMemoryDocumentRepository();
        OTService ot = new OTService(repo);

        ot.applyOp("S", insert(0, "hello"));
        assertThat(ot.getDocument("S")).isEqualTo("hello");

        ot.persist("S");
        assertThat(repo.load("S")).get().extracting(StoredDocument::text).isEqualTo("hello");

        // Simulate the room going cold in memory but staying in the repository,
        // then a reconnect reloading it (load-on-miss) instead of a blank doc.
        OTService reborn = new OTService(repo);
        assertThat(reborn.getDocument("S")).isEqualTo("hello");
        assertThat(reborn.getVersion("S")).isEqualTo(1);
    }

    @Test
    void evictRemovesBothResidentStoreAndDurableSnapshot() {
        DocumentRepository repo = new InMemoryDocumentRepository();
        OTService ot = new OTService(repo);

        ot.applyOp("S", insert(0, "data"));
        ot.persist("S");
        assertThat(repo.load("S")).isPresent();

        ot.evict("S");
        assertThat(repo.load("S")).isEmpty();
        // A fresh getOrCreate now starts blank — the room is truly gone.
        assertThat(ot.getDocument("S")).isEmpty();
    }

    @Test
    void persistAllSnapshotsEveryResidentRoom() {
        DocumentRepository repo = new InMemoryDocumentRepository();
        OTService ot = new OTService(repo);
        ot.applyOp("A", insert(0, "aaa"));
        ot.applyOp("B", insert(0, "bbb"));

        ot.persistAll();

        assertThat(repo.load("A")).get().extracting(StoredDocument::text).isEqualTo("aaa");
        assertThat(repo.load("B")).get().extracting(StoredDocument::text).isEqualTo("bbb");
    }
}
