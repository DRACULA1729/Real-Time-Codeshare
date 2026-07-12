package com.collabcode.realtime_codeshare.persistence;

/**
 * A persisted document snapshot: the full text, the version it was taken at, and the
 * shared base file name ({@code ""} when never renamed; pre-existing snapshots without
 * a name deserialize to {@code ""}).
 */
public record StoredDocument(String text, int version, String name) {
    public StoredDocument {
        if (name == null) name = "";
    }
}
