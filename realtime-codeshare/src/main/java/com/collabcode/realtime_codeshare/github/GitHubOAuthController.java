package com.collabcode.realtime_codeshare.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    public void authorize(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!props.isConfigured()) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "GitHub OAuth is not configured");
            return;
        }
        String state = randomState();
        req.getSession(true).setAttribute(STATE_ATTR, state);
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
        resp.sendRedirect("/?github=connected");
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
        if (session != null) tokenStore.remove(session.getId());
        return Map.of("authorized", false);
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
