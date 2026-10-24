package com.vgu.client.models.identity;

public class User {
    private String userID;
    private String username;
    private byte[] passwordHash;
    private byte[] salt;

    User() {}
}
