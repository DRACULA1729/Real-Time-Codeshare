package com.collabcode.realtime_codeshare.controller;

import com.collabcode.realtime_codeshare.github.GitHubApiException;
import com.collabcode.realtime_codeshare.github.GitHubAuthException;
import com.collabcode.realtime_codeshare.github.GitHubProperties;
import com.collabcode.realtime_codeshare.github.GitHubTokenStore;
import com.collabcode.realtime_codeshare.ot.OTService;
import com.collabcode.realtime_codeshare.service.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pushes the live session document to GitHub using the caller's own server-held
 * OAuth token. The token is never accepted from, or returned to, the browser.
 */
@RestController
@RequestMapping("/api/github")
public class GitHubController {

    private static final String API_VERSION = "2022-11-28";

    private final OTService otService;
    private final SessionService sessionService;
    private final GitHubTokenStore tokenStore;
    private final GitHubProperties props;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubController(OTService otService, SessionService sessionService,
                            GitHubTokenStore tokenStore, GitHubProperties props,
                            RestClient githubRestClient) {
        this.otService = otService;
        this.sessionService = sessionService;
        this.tokenStore = tokenStore;
        this.props = props;
        this.restClient = githubRestClient;
    }

    public static class PushRequest {
        @NotBlank public String owner;
        @NotBlank public String repo;
        @NotBlank
        @Pattern(regexp = "^(?!/)(?!.*\\.\\.).+$", message = "must not start with '/' or contain '..'")
        public String path;            // e.g. "src/Main.java"
        public String message;         // commit message (optional)
        public String branch;          // blank -> repository default branch
        @NotBlank public String sessionId;     // which session's document to push
        @NotBlank public String sessionToken;  // caller's member token (authz)
    }

    @PostMapping("/push")
    public Map<String, Object> push(@Valid @RequestBody PushRequest req, HttpServletRequest httpReq) {
        // 1. The session must exist and the caller must be admitted to it.
        if (!SessionService.isValidIdFormat(req.sessionId) || !sessionService.exists(req.sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown session");
        }
        if (!sessionService.isAdmitted(req.sessionId, req.sessionToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not admitted to this session");
        }

        // 2. Resolve this browser's GitHub token (held server-side).
        HttpSession httpSession = httpReq.getSession(false);
        String ghToken = httpSession == null ? null : tokenStore.get(httpSession.getId());
        if (ghToken == null) {
            throw new GitHubAuthException("connect a GitHub account first");
        }

        // 3. Push the current document.
        String content = otService.getDocument(req.sessionId);
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String branch = (req.branch == null || req.branch.isBlank())
                ? resolveDefaultBranch(req.owner, req.repo, ghToken)
                : req.branch.trim();
        String url = contentsUrl(req.owner, req.repo, req.path);

        String existingSha = fetchSha(url, branch, ghToken);

        Map<String, Object> body = new HashMap<>();
        body.put("message", (req.message == null || req.message.isBlank()) ? "Update from Collab CodeShare" : req.message);
        body.put("content", encoded);
        body.put("branch", branch);
        if (existingSha != null) body.put("sha", existingSha);

        JsonNode put = exchange("PUT", url, ghToken, body);
        return Map.of(
                "ok", true,
                "path", req.path,
                "branch", branch,
                "commitSha", put.path("commit").path("sha").asText(""),
                "htmlUrl", put.path("content").path("html_url").asText("")
        );
    }

    private String resolveDefaultBranch(String owner, String repo, String token) {
        String url = props.getApiBase() + "/repos/" + enc(owner) + "/" + enc(repo);
        JsonNode repoNode = exchange("GET", url, token, null);
        String def = repoNode.path("default_branch").asText("");
        return def.isBlank() ? "main" : def;
    }

    /** GET the file to learn its blob sha; returns null when the file does not yet exist. */
    private String fetchSha(String url, String branch, String token) {
        try {
            JsonNode node = exchange("GET", url + "?ref=" + enc(branch), token, null);
            String sha = node.path("sha").asText(null);
            return (sha == null || sha.isBlank()) ? null : sha;
        } catch (GitHubApiException e) {
            if (e.getStatus().value() == 404) return null; // file absent -> create
            throw e;
        }
    }

    /** Single point for GitHub calls: sets headers, parses JSON, maps errors to GitHubApiException. */
    private JsonNode exchange(String method, String url, String token, Map<String, Object> body) {
        try {
            RestClient.RequestBodySpec spec = restClient.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", API_VERSION);
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(serialize(body));
            }
            String resp = spec.retrieve().body(String.class);
            return mapper.readTree(resp == null || resp.isBlank() ? "{}" : resp);
        } catch (RestClientResponseException e) {
            throw new GitHubApiException(e.getStatusCode(), gitHubMessage(e.getResponseBodyAsString(), e.getStatusCode().value()));
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new GitHubApiException(HttpStatus.BAD_GATEWAY, "GitHub request failed");
        }
    }

    /** Extract GitHub's "message" field for a clean error, never echoing internals. */
    private String gitHubMessage(String responseBody, int status) {
        try {
            JsonNode node = mapper.readTree(responseBody == null ? "{}" : responseBody);
            String msg = node.path("message").asText("");
            return msg.isBlank() ? ("GitHub returned " + status) : msg;
        } catch (Exception ignored) {
            return "GitHub returned " + status;
        }
    }

    private String serialize(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new GitHubApiException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to build request");
        }
    }

    private String contentsUrl(String owner, String repo, String path) {
        return props.getApiBase() + "/repos/" + enc(owner) + "/" + enc(repo) + "/contents/" + encPath(path);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Encode each path segment but keep the separating slashes. */
    private static String encPath(String path) {
        return java.util.Arrays.stream(path.split("/"))
                .map(GitHubController::enc)
                .collect(Collectors.joining("/"));
    }
}
