package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.security.CsrfFilter;
import com.collabcode.realtime_codeshare.security.SecurityHeadersFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the hand-rolled security filters with explicit, deterministic order.
 * Security-headers runs first (so even a CSRF-rejection response carries the
 * headers), then the CSRF guard. Explicit {@link FilterRegistrationBean}s are used
 * instead of {@code @Component} so the filter-chain position can't drift.
 */
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> reg = new FilterRegistrationBean<>(new SecurityHeadersFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<CsrfFilter> csrfFilter() {
        FilterRegistrationBean<CsrfFilter> reg = new FilterRegistrationBean<>(new CsrfFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return reg;
    }
}
