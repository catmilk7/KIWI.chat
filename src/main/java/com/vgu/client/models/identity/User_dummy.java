package com.vgu.client.models.identity;

public class User_dummy {
    private String id;
    private String username;
    private String displayName;
    private String bio;
    private boolean online;

    public User_dummy(String id, String username, String displayName, String bio, boolean online) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.bio = bio;
        this.online = online;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getBio() { return bio; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
}
