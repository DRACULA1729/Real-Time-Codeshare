package com.collabcode.realtime_codeshare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Stateless double-submit CSRF guard for cookie-authenticated, state-changing API
 * calls (e.g. {@code /api/github/push}, {@code /disconnect}). A JS-readable
 * {@code XSRF-TOKEN} cookie is issued on first contact; the SPA echoes it in the
 * {@code X-CSRF-Token} header on unsafe requests, and the filter requires the two
 * to match. Being cookie+header (not server session state) it works across nodes.
 *
 * <p>GET/HEAD/OPTIONS are exempt (safe). The WebSocket upgrade ({@code GET /ws/ot})
 * is exempt and independently gated by the single-use connection ticket. Combined
 * with {@code SameSite=Lax} cookies this is defense-in-depth.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CsrfFilter extends OncePerRequestFilter {

    static final String COOKIE = "XSRF-TOKEN";
    static final String HEADER = "X-CSRF-Token";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String token = readCookie(req);
        if (token == null) {
            token = randomToken();
            ResponseCookie cookie = ResponseCookie.from(COOKIE, token)
                    .path("/")
                    .httpOnly(false)            // the SPA must read it to echo it back
                    .secure(req.isSecure())     // Secure over HTTPS (honors X-Forwarded-Proto)
                    .sameSite("Lax")
                    .build();
            resp.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }

        if (requiresCheck(req)) {
            String header = req.getHeader(HEADER);
            if (header == null || !constantTimeEquals(header, token)) {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF token missing or invalid");
                return;
            }
        }
        chain.doFilter(req, resp);
    }

    /** Unsafe method against the REST API. The WS upgrade and static assets are exempt. */
    private boolean requiresCheck(HttpServletRequest req) {
        if (SAFE.contains(req.getMethod())) return false;
        String path = req.getRequestURI();
        return path != null && path.startsWith("/api/");
    }

    private String readCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    private String randomToken() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
