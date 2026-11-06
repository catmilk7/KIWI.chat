package com.vgu.client.models.identity;

public abstract class User {
    protected long userID;
    protected String username;
    protected String displayName;

    // Initialize
    public User() {}

    public User(long userID, String username, String displayName){
        this.userID = userID;
        this.username = username;
        this.displayName = displayName;
    }

    public long getUserID() { return userID; }
    public void setUserID(long userID) { this.userID = userID; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String setDisplayName() { return displayName; }
    public void setDisplayName(long userID) { this.displayName = displayName; }
}
