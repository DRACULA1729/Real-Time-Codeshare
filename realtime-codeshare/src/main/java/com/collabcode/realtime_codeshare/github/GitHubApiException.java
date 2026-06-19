package com.collabcode.realtime_codeshare.github;

import org.springframework.http.HttpStatusCode;

/** A GitHub API call returned an error. Carries a sanitized message (never the token). */
public class GitHubApiException extends RuntimeException {
    private final HttpStatusCode status;

    public GitHubApiException(HttpStatusCode status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatusCode getStatus() {
        return status;
    }
}
