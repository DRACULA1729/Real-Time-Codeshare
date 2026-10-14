package com.collabcode.realtime_codeshare.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void setsCspAndHardeningHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());

        String csp = resp.getHeader("Content-Security-Policy");
        assertThat(csp).contains("default-src 'self'");
        assertThat(csp).contains("script-src 'self'");
        assertThat(csp).contains("frame-ancestors 'none'");
        assertThat(csp).doesNotContain("script-src 'self' 'unsafe-inline'"); // script stays strict
        assertThat(resp.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(resp.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(resp.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void hstsOnlyOverHttps() throws Exception {
        MockHttpServletRequest http = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        filter.doFilter(http, r1, new MockFilterChain());
        assertThat(r1.getHeader("Strict-Transport-Security")).isNull();

        MockHttpServletRequest https = new MockHttpServletRequest("GET", "/");
        https.setSecure(true);
        MockHttpServletResponse r2 = new MockHttpServletResponse();
        filter.doFilter(https, r2, new MockFilterChain());
        assertThat(r2.getHeader("Strict-Transport-Security")).contains("max-age=");
    }
}
