package com.collabcode.realtime_codeshare.persistence;

/** A persisted document snapshot: the full text and the version it was taken at. */
public record StoredDocument(String text, int version) {}
