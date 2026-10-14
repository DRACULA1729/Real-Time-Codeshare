package com.collabcode.realtime_codeshare.github;

/** The caller has not connected a GitHub account (no server-held token for this browser). */
public class GitHubAuthException extends RuntimeException {
    public GitHubAuthException(String message) {
        super(message);
    }
}
