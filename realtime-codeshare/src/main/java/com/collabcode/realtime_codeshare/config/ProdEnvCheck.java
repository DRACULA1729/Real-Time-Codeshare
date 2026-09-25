package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.github.GitHubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast guard for the {@code prod} profile: refuses to start with a clear
 * message if launch-critical configuration is missing or obviously wrong, instead
 * of silently falling back to localhost defaults (which would reject every WS
 * upgrade, or send the GitHub OAuth callback to localhost).
 */
@Component
@Profile("prod")
public class ProdEnvCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProdEnvCheck.class);

    private final String allowedOrigins;
    private final GitHubProperties github;
    private final Environment environment;

    public ProdEnvCheck(@Value("${codeshare.allowed-origins:}") String allowedOrigins,
                        GitHubProperties github,
                        Environment environment) {
        this.allowedOrigins = allowedOrigins == null ? "" : allowedOrigins.trim();
        this.github = github;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> errors = new ArrayList<>();

        if (allowedOrigins.isBlank()) {
            errors.add("CODESHARE_ALLOWED_ORIGINS is required in prod (set it to the exact origin, e.g. https://codeshare.example.com)");
        } else if (allowedOrigins.contains("localhost") || allowedOrigins.contains("127.0.0.1")) {
            errors.add("CODESHARE_ALLOWED_ORIGINS must not contain localhost/127.0.0.1 in prod: " + allowedOrigins);
        } else if (allowedOrigins.contains("*")) {
            log.warn("CODESHARE_ALLOWED_ORIGINS contains a wildcard ({}); prefer exact origins in prod", allowedOrigins);
        }

        // Only postgres survives a restart: the default in-memory repo and bare redis
        // (non-durable cache) would silently lose every document on redeploy.
        if (!environment.acceptsProfiles(Profiles.of("postgres"))) {
            errors.add("prod requires the 'postgres' profile for restart-durable documents "
                    + "(e.g. SPRING_PROFILES_ACTIVE=prod,postgres with DB_URL/DB_USER/DB_PASSWORD)");
        }

        if (github.isConfigured()) {
            String redirect = github.getRedirectUri();
            if (redirect == null || redirect.isBlank() || redirect.contains("localhost")) {
                errors.add("GITHUB_REDIRECT_URI must be set to the public https callback when GitHub push is enabled (got: " + redirect + ")");
            } else if (!redirect.startsWith("https://")) {
                errors.add("GITHUB_REDIRECT_URI must be https in prod (got: " + redirect + ")");
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start with the 'prod' profile due to missing/invalid configuration:\n  - "
                            + String.join("\n  - ", errors));
        }
        log.info("Prod configuration check passed (origins set, GitHub {}).",
                github.isConfigured() ? "configured" : "disabled");
    }
}
