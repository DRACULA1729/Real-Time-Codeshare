package com.collabcode.realtime_codeshare.model;

import com.collabcode.realtime_codeshare.security.Role;

import java.util.Objects;

public class User {
    private String name;
    private String ipAddress;
    private Role role;
    private String color;
    private boolean isEditing=false;
    private int rejectedAttempts=0;

    public User(String name, String ipAddress, Role role, String color) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.role = role;
        this.color = color;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public boolean isEditing() {
        return isEditing;
    }

    public void setEditing(boolean editing) {
        isEditing = editing;
    }

    public int getRejectedAttempts() {
        return rejectedAttempts;
    }

    public void setRejectedAttempts(int rejectedAttempts) {
        this.rejectedAttempts = rejectedAttempts;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof User user)) return false;
        return Objects.equals(getIpAddress(), user.getIpAddress());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getIpAddress());
    }
}
