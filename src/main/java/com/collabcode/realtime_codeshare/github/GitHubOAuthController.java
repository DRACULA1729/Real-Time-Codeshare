package com.collabcode.realtime_codeshare.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hand-rolled GitHub OAuth2 authorization-code flow (no Spring Security). The
 * access token is exchanged server-side and stored per HTTP session; it never
 * reaches the browser.
 */
@RestController
@RequestMapping("/api/github")
public class GitHubOAuthController {

    private static final Logger log = LoggerFactory.getLogger(GitHubOAuthController.class);
    private static final String STATE_ATTR = "gh_oauth_state";
    private static final String RETURN_ATTR = "gh_oauth_return";

    private final GitHubProperties props;
    private final GitHubTokenStore tokenStore;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public GitHubOAuthController(GitHubProperties props, GitHubTokenStore tokenStore, RestClient githubRestClient) {
        this.props = props;
        this.tokenStore = tokenStore;
        this.restClient = githubRestClient;
    }

    @GetMapping("/authorize")
    public void authorize(@RequestParam(required = false) String returnTo,
                          HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!props.isConfigured()) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "GitHub OAuth is not configured");
            return;
        }
        String state = randomState();
        HttpSession session = req.getSession(true);
        session.setAttribute(STATE_ATTR, state);
        // Only same-origin relative paths are honored, to prevent open redirects.
        if (returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//")) {
            session.setAttribute(RETURN_ATTR, returnTo);
        }
        String url = props.getAuthorizeUri()
                + "?client_id=" + enc(props.getClientId())
                + "&redirect_uri=" + enc(props.getRedirectUri())
                + "&scope=" + enc(props.getScope())
                + "&state=" + enc(state);
        resp.sendRedirect(url);
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        Object expected = session == null ? null : session.getAttribute(STATE_ATTR);
        if (code == null || state == null || expected == null || !expected.equals(state)) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "invalid OAuth state");
            return;
        }
        session.removeAttribute(STATE_ATTR);

        String token = exchangeCodeForToken(code);
        tokenStore.put(session.getId(), token);
        log.info("GitHub account connected for HTTP session {}", session.getId());

        Object returnTo = session.getAttribute(RETURN_ATTR);
        session.removeAttribute(RETURN_ATTR);
        String dest = returnTo instanceof String s && !s.isBlank() ? s : "/";
        dest += (dest.contains("?") ? "&" : "?") + "github=connected";
        resp.sendRedirect(dest);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        boolean authorized = session != null && tokenStore.has(session.getId());
        return Map.of("authorized", authorized, "configured", props.isConfigured());
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            String token = tokenStore.get(session.getId());
            tokenStore.remove(session.getId());
            if (token != null) revokeAsync(token);
        }
        return Map.of("authorized", false);
    }

    /** Best-effort revocation at GitHub, off the request thread so it never delays
     *  the user's disconnect. The token is already gone locally either way. */
    private void revokeAsync(String token) {
        if (!props.isConfigured()) return;
        CompletableFuture.runAsync(() -> {
            try {
                restClient.method(HttpMethod.DELETE)
                        .uri(props.getApiBase() + "/applications/{clientId}/token", props.getClientId())
                        .headers(h -> h.setBasicAuth(props.getClientId(), props.getClientSecret()))
                        .header("Accept", "application/vnd.github+json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("access_token", token))
                        .retrieve()
                        .toBodilessEntity();
            } catch (RuntimeException e) {
                log.warn("GitHub token revocation failed (token already dropped locally): {}", e.getMessage());
            }
        });
    }

    private String exchangeCodeForToken(String code) {
        String form = "client_id=" + enc(props.getClientId())
                + "&client_secret=" + enc(props.getClientSecret())
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(props.getRedirectUri());
        try {
            String body = restClient.post()
                    .uri(props.getTokenUri())
                    .header("Accept", "application/json")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode node = mapper.readTree(body == null ? "{}" : body);
            String token = node.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                String err = node.path("error_description").asText(node.path("error").asText("token exchange failed"));
                throw new GitHubAuthException(err);
            }
            return token;
        } catch (RestClientException | IOException e) {
            throw new GitHubAuthException("GitHub token exchange failed");
        }
    }

    private String randomState() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
