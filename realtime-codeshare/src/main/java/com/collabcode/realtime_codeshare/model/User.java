package com.collabcode.realtime_codeshare.model;

import com.collabcode.realtime_codeshare.security.Role;

import java.util.Objects;

/**
 * A participant in a session. Identity is the server-minted {@code token}
 * (never the IP), which is what equals/hashCode key on. {@code clientId} is the
 * public, client-chosen OT/display id and carries no authority.
 */
public class User {
    private final String token;
    private String clientId;
    private String name;
    private String color;
    private Role role;

    public User(String token, String clientId, String name, String color, Role role) {
        this.token = token;
        this.clientId = clientId;
        this.name = name;
        this.color = color;
        this.role = role;
    }

    public String getToken() { return token; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof User user)) return false;
        return Objects.equals(token, user.token);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(token);
    }
}
