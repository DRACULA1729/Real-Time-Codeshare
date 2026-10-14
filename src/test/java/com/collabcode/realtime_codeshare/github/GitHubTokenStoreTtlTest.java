package com.collabcode.realtime_codeshare.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Server-held GitHub tokens must expire (bounded memory) and not linger forever. */
class GitHubTokenStoreTtlTest {

    @Test
    void tokenIsReturnedBeforeTtl() {
        GitHubTokenStore store = new GitHubTokenStore(10_000);
        store.put("session-1", "gho_abc");
        assertThat(store.get("session-1")).isEqualTo("gho_abc");
        assertThat(store.has("session-1")).isTrue();
    }

    @Test
    void tokenExpiresAfterTtl() throws InterruptedException {
        GitHubTokenStore store = new GitHubTokenStore(30);
        store.put("session-1", "gho_abc");
        Thread.sleep(60);
        assertThat(store.get("session-1")).isNull();
        assertThat(store.has("session-1")).isFalse();
    }

    @Test
    void removeClearsImmediately() {
        GitHubTokenStore store = new GitHubTokenStore(10_000);
        store.put("session-1", "gho_abc");
        store.remove("session-1");
        assertThat(store.has("session-1")).isFalse();
    }
}
