package com.collabcode.realtime_codeshare.ot;

/**
 * Thrown by {@code DocumentStore.commit} when an incoming op's {@code baseVersion}
 * is below the history-compaction floor: the ops needed to transform it correctly
 * have already been discarded, so transforming against partial history would
 * silently diverge. The handler responds by forcing the offending client to
 * re-sync (rebasing its unacked edits onto the authoritative snapshot) rather
 * than applying a corrupt transform.
 */
public class ResyncRequiredException extends RuntimeException {
    public ResyncRequiredException(String message) {
        super(message);
    }
}
