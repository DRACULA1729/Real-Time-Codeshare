package com.collabcode.realtime_codeshare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds strict, same-origin security headers to every response. Spring Security is
 * intentionally off (auth is custom over the WebSocket), so these are hand-rolled.
 *
 * <p>The SPA is a self-contained, same-origin bundle: external module script +
 * stylesheet under {@code /assets}, bundled fonts, no CDN. So a tight CSP holds.
 * The one allowance is {@code 'unsafe-inline'} in {@code style-src}: React renders
 * dynamic colors via inline {@code style} attributes (cursor flags, avatars), which
 * a nonce can't cover. Inline style injection is low risk versus script; script-src
 * stays strict {@code 'self'} (the theme bootstrap was externalized to enable this).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",   // same-origin ws/wss is covered by 'self' in CSP3
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        resp.setHeader("Content-Security-Policy", CSP);
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-Frame-Options", "DENY");
        resp.setHeader("Referrer-Policy", "no-referrer");
        resp.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        // HSTS is only honored over HTTPS; isSecure() reflects X-Forwarded-Proto when
        // forward-headers-strategy=framework, so it lights up behind a TLS proxy.
        if (req.isSecure()) {
            resp.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        chain.doFilter(req, resp);
    }
}
