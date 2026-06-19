package com.collabcode.realtime_codeshare.ot;

/**
 * Thrown when an incoming op fails validation. The WebSocket handler catches
 * this, logs a warning, and ignores the op — a malformed message from one
 * client must never crash the room.
 */
public class InvalidOpException extends RuntimeException {
    public InvalidOpException(String message) {
        super(message);
    }
}
