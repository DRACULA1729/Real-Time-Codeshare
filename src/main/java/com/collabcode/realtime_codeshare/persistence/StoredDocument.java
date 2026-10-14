package com.collabcode.realtime_codeshare.persistence;

/**
 * A persisted document snapshot: the full text, the version it was taken at, and the
 * shared base file name ({@code ""} when never renamed) and the language chosen for it
 * ({@code ""} = auto-detect). Snapshots written before either field existed read as {@code ""}.
 */
public record StoredDocument(String text, int version, String name, String language) {
    public StoredDocument {
        if (name == null) name = "";
        if (language == null) language = "";
    }
}
