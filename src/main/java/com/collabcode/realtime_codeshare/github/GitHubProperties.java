package com.collabcode.realtime_codeshare.github;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GitHub OAuth + API configuration. Empty client credentials are valid — the app
 * boots and runs, the push UI just degrades gracefully ({@link #isConfigured()}).
 */
@Component
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    private String clientId = "";
    private String clientSecret = "";
    private String scope = "public_repo";
    private String redirectUri = "http://localhost:8080/api/github/callback";
    private String apiBase = "https://api.github.com";
    private String authorizeUri = "https://github.com/login/oauth/authorize";
    private String tokenUri = "https://github.com/login/oauth/access_token";

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

    public String getApiBase() { return apiBase; }
    public void setApiBase(String apiBase) { this.apiBase = apiBase; }

    public String getAuthorizeUri() { return authorizeUri; }
    public void setAuthorizeUri(String authorizeUri) { this.authorizeUri = authorizeUri; }

    public String getTokenUri() { return tokenUri; }
    public void setTokenUri(String tokenUri) { this.tokenUri = tokenUri; }
}
