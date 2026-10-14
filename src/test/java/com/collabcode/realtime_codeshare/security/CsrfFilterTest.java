package com.collabcode.realtime_codeshare.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfFilterTest {

    private final CsrfFilter filter = new CsrfFilter();

    @Test
    void getIssuesTheCookieAndIsNeverBlocked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("Set-Cookie")).contains("XSRF-TOKEN=");
        assertThat(chain.getRequest()).as("request proceeded").isNotNull();
    }

    @Test
    void postToApiWithoutTokenIsForbidden() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/sessions");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).as("request was NOT forwarded").isNull();
    }

    @Test
    void postToApiWithMatchingCookieAndHeaderPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/sessions");
        req.setCookies(new Cookie("XSRF-TOKEN", "tok-123"));
        req.addHeader("X-CSRF-Token", "tok-123");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void postToApiWithMismatchedHeaderIsForbidden() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/github/push");
        req.setCookies(new Cookie("XSRF-TOKEN", "tok-123"));
        req.addHeader("X-CSRF-Token", "WRONG");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void nonApiPostIsNotChecked() throws Exception {
        // The WS upgrade and any non-/api path are exempt (ticket-gated / static).
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/something");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);

        assertThat(chain.getRequest()).isNotNull();
    }
}
