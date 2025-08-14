package com.collabcode.realtime_codeshare.model;

import java.util.*;

import java.util.concurrent.ConcurrentHashMap;

public class Session{
    private String sessionId;
    private User creator;
    private Set<User> activeUsers= ConcurrentHashMap.newKeySet();
    private List<String> chatLog= new ArrayList<>();
    private Set<String> ipWhitelist=ConcurrentHashMap.newKeySet();
    private Queue<User> pendingJoinRequests=new LinkedList<>();
    private boolean isAlive;


    public Session(String sessionId, User creator) {
        this.sessionId = sessionId;
        this.creator = creator;
        this.activeUsers.add(creator);
        this.ipWhitelist.add(creator.getIpAddress());
    }


    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public User getCreator() {
        return creator;
    }

    public void setCreator(User creator) {
        this.creator = creator;
    }

    public Set<User> getActiveUsers() {
        return activeUsers;
    }

    public void setActiveUsers(Set<User> activeUsers) {
        this.activeUsers = activeUsers;
    }

    public List<String> getChatLog() {
        return chatLog;
    }

    public void setChatLog(List<String> chatLog) {
        this.chatLog = chatLog;
    }

    public Set<String> getIpWhitelist() {
        return ipWhitelist;
    }

    public void setIpWhitelist(Set<String> ipWhitelist) {
        this.ipWhitelist = ipWhitelist;
    }

    public Queue<User> getPendingJoinRequests() {
        return pendingJoinRequests;
    }

    public void setPendingJoinRequests(Queue<User> pendingJoinRequests) {
        this.pendingJoinRequests = pendingJoinRequests;
    }

    public boolean isAlive() {
        return isAlive;
    }

    public void setAlive(boolean alive) {
        isAlive = alive;
    }

    public void addToWhiteList(String ip){
        ipWhitelist.add(ip);
    }
    public void removeUser(User user){
        activeUsers.remove(user);
    }
    public void clear(){
        activeUsers.clear();
        chatLog.clear();
        ipWhitelist.clear();
        pendingJoinRequests.clear();
    }






}

