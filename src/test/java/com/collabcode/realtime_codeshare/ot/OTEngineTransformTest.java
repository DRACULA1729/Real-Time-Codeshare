package com.collabcode.realtime_codeshare.ot;

import com.collabcode.realtime_codeshare.ot.OTService.DeleteOp;
import com.collabcode.realtime_codeshare.ot.OTService.InsertOp;
import com.collabcode.realtime_codeshare.ot.OTService.Op;
import com.collabcode.realtime_codeshare.ot.OTService.OTEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises every branch of the OT transform function. */
class OTEngineTransformTest {

    private static InsertOp ins(String client, int pos, String text) {
        return new InsertOp(client, 0, pos, text);
    }

    private static DeleteOp del(String client, int pos, int len) {
        return new DeleteOp(client, 0, pos, len);
    }

    // ---- insert vs insert ----

    @Test
    void insertBeforeInsert_unchanged() {
        InsertOp r = (InsertOp) OTEngine.transform(ins("a", 2, "x"), ins("b", 5, "yy"));
        assertEquals(2, r.getPos());
    }

    @Test
    void insertAfterInsert_shiftsRight() {
        InsertOp r = (InsertOp) OTEngine.transform(ins("a", 5, "x"), ins("b", 2, "yy"));
        assertEquals(7, r.getPos());
    }

    @Test
    void insertTie_lowerClientWins_noShift() {
        // a's clientId "a" < "b" => a wins the tie => stays put.
        InsertOp r = (InsertOp) OTEngine.transform(ins("a", 3, "x"), ins("b", 3, "yy"));
        assertEquals(3, r.getPos());
    }

    @Test
    void insertTie_higherClientLoses_shifts() {
        // a's clientId "z" > "b" => a loses => shifts by b's length.
        InsertOp r = (InsertOp) OTEngine.transform(ins("z", 3, "x"), ins("b", 3, "yy"));
        assertEquals(5, r.getPos());
    }

    @Test
    void insertTie_nullClientIds_doesNotThrow() {
        assertDoesNotThrow(() -> OTEngine.transform(ins(null, 1, "x"), ins(null, 1, "y")));
    }

    // ---- insert vs delete ----

    @Test
    void insertAtOrBeforeDeleteStart_unchanged() {
        InsertOp r = (InsertOp) OTEngine.transform(ins("a", 2, "x"), del("b", 2, 3));
        assertEquals(2, r.getPos());
    }

    @Test
    void insertAfterDeleteRange_shiftsLeftByLength() {
        InsertOp r = (InsertOp) OTEngine.transform(ins("a", 10, "x"), del("b", 2, 3));
        assertEquals(7, r.getPos());
    }

    @Test
    void insertInsideDeleteRange_collapsesToDeleteStart() {
        InsertOp r = (InsertOp) OTEngine.transform(ins("a", 4, "x"), del("b", 2, 5));
        assertEquals(2, r.getPos());
    }

    // ---- delete vs insert ----

    @Test
    void deleteAfterInsert_shiftsRight() {
        DeleteOp r = (DeleteOp) OTEngine.transform(del("a", 5, 2), ins("b", 1, "xy"));
        assertEquals(7, r.getPos());
        assertEquals(2, r.getLength());
    }

    @Test
    void insertInsideDeleteRange_growsDeleteLength() {
        DeleteOp r = (DeleteOp) OTEngine.transform(del("a", 2, 5), ins("b", 4, "xyz"));
        assertEquals(2, r.getPos());
        assertEquals(8, r.getLength());
    }

    @Test
    void insertAfterDeleteRange_deleteUnchanged() {
        DeleteOp r = (DeleteOp) OTEngine.transform(del("a", 2, 3), ins("b", 10, "x"));
        assertEquals(2, r.getPos());
        assertEquals(3, r.getLength());
    }

    // ---- delete vs delete ----

    @Test
    void deleteDisjointBefore_unchanged() {
        DeleteOp r = (DeleteOp) OTEngine.transform(del("a", 0, 2), del("b", 5, 2));
        assertEquals(0, r.getPos());
        assertEquals(2, r.getLength());
    }

    @Test
    void deleteDisjointAfter_shiftsLeft() {
        DeleteOp r = (DeleteOp) OTEngine.transform(del("a", 5, 2), del("b", 0, 3));
        assertEquals(2, r.getPos());
        assertEquals(2, r.getLength());
    }

    @Test
    void deleteOverlap_reducesLength() {
        // a deletes [2,7), b deletes [4,9) => overlap [4,7) of length 3 removed from a.
        DeleteOp r = (DeleteOp) OTEngine.transform(del("a", 2, 5), del("b", 4, 5));
        assertEquals(2, r.getPos());
        assertEquals(2, r.getLength());
    }

    @Test
    void deleteFullyContained_collapsesToZero() {
        // a deletes [3,6) entirely inside b's [2,8) => a becomes empty.
        DeleteOp r = (DeleteOp) OTEngine.transform(del("a", 3, 3), del("b", 2, 6));
        assertEquals(0, r.getLength());
    }

    @Test
    void deleteLengthNeverNegative() {
        Op r = OTEngine.transform(del("a", 3, 1), del("b", 0, 10));
        assertTrue(((DeleteOp) r).getLength() >= 0);
    }
}
