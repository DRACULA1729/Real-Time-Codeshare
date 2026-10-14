package com.collabcode.realtime_codeshare.github;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Disconnect drops the token locally and revokes it at GitHub (best-effort, async). */
class GitHubTokenRevocationTest {

    record Call(String method, String path, String auth, String body) {}

    @Test
    void disconnectRevokesTheTokenAtGitHub() throws Exception {
        CompletableFuture<Call> seen = new CompletableFuture<>();
        HttpServer github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/", ex -> {
            seen.complete(new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("Authorization"),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        github.start();
        try {
            GitHubProperties props = new GitHubProperties();
            props.setClientId("cid");
            props.setClientSecret("secret");
            props.setApiBase("http://127.0.0.1:" + github.getAddress().getPort());
            GitHubTokenStore store = new GitHubTokenStore(60_000);
            GitHubOAuthController controller =
                    new GitHubOAuthController(props, store, new GitHubClientConfig().githubRestClient());

            MockHttpServletRequest req = new MockHttpServletRequest();
            MockHttpSession session = new MockHttpSession();
            req.setSession(session);
            store.put(session.getId(), "gho_secret");

            controller.disconnect(req);
            assertThat(store.has(session.getId())).as("dropped locally at once").isFalse();

            Call call = seen.get(5, TimeUnit.SECONDS);
            assertThat(call.method()).isEqualTo("DELETE");
            assertThat(call.path()).isEqualTo("/applications/cid/token");
            assertThat(call.auth()).isEqualTo("Basic " + Base64.getEncoder()
                    .encodeToString("cid:secret".getBytes(StandardCharsets.UTF_8)));
            assertThat(call.body()).contains("\"access_token\":\"gho_secret\"");
        } finally {
            github.stop(0);
        }
    }
}
