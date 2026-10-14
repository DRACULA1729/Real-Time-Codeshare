package com.collabcode.realtime_codeshare.ot;

import com.collabcode.realtime_codeshare.ot.OTService.OpDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Each malformed op must be rejected with InvalidOpException and leave store state untouched. */
class OTServiceValidationTest {

    private OpDTO op(String clientId, String type, int baseVersion, int pos, String text, Integer length) {
        OpDTO d = new OpDTO();
        d.clientId = clientId;
        d.type = type;
        d.baseVersion = baseVersion;
        d.pos = pos;
        d.text = text;
        d.length = length;
        return d;
    }

    private void assertRejected(OpDTO dto) {
        OTService svc = new OTService(new com.collabcode.realtime_codeshare.persistence.InMemoryDocumentRepository());
        assertThrows(InvalidOpException.class, () -> svc.applyOp("room", dto));
        // Validation runs before the store is created => no side effect at all.
        assertFalse(svc.hasStore("room"), "rejected op must not create or mutate a store");
    }

    @Test
    void noOpsAndCarriageReturns_rejected() {
        assertRejected(op("a", "insert", 0, 0, "", null));
        assertRejected(op("a", "delete", 0, 0, null, 0));
        assertRejected(op("a", "insert", 0, 0, "a\r\nb", null));
    }

    @Test
    void baseVersionAheadOfTheDocument_rejected() {
        OTService svc = new OTService(new com.collabcode.realtime_codeshare.persistence.InMemoryDocumentRepository());
        svc.applyOp("room", op("a", "insert", 0, 0, "x", null));
        assertThrows(InvalidOpException.class, () -> svc.applyOp("room", op("a", "insert", 5, 0, "y", null)));
        assertEquals("x", svc.getDocument("room"));
    }

    @Test
    void nullClientId_rejected() {
        assertRejected(op(null, "insert", 0, 0, "x", null));
    }

    @Test
    void blankClientId_rejected() {
        assertRejected(op("   ", "insert", 0, 0, "x", null));
    }

    @Test
    void nullType_rejected() {
        assertRejected(op("a", null, 0, 0, "x", null));
    }

    @Test
    void unknownType_rejected() {
        assertRejected(op("a", "frobnicate", 0, 0, "x", null));
    }

    @Test
    void negativeBaseVersion_rejected() {
        assertRejected(op("a", "insert", -1, 0, "x", null));
    }

    @Test
    void negativePos_rejected() {
        assertRejected(op("a", "insert", 0, -1, "x", null));
    }

    @Test
    void oversizeInsert_rejected() {
        String huge = "x".repeat(OTService.MAX_OP_LEN + 1);
        assertRejected(op("a", "insert", 0, 0, huge, null));
    }

    @Test
    void negativeDeleteLength_rejected() {
        assertRejected(op("a", "delete", 0, 0, null, -5));
    }

    @Test
    void oversizeDeleteLength_rejected() {
        assertRejected(op("a", "delete", 0, 0, null, OTService.MAX_OP_LEN + 1));
    }

    @Test
    void validOp_isAccepted_andExistingStateSurvivesLaterRejection() {
        OTService svc = new OTService(new com.collabcode.realtime_codeshare.persistence.InMemoryDocumentRepository());
        svc.applyOp("room", op("a", "insert", 0, 0, "Hello", null));
        assertEquals("Hello", svc.getDocument("room"));
        assertEquals(1, svc.getVersion("room"));

        // A subsequent invalid op must not corrupt the established document.
        assertThrows(InvalidOpException.class,
                () -> svc.applyOp("room", op("a", "insert", 0, -3, "bad", null)));
        assertEquals("Hello", svc.getDocument("room"));
        assertEquals(1, svc.getVersion("room"));
    }
}
