package com.vgu.client.models.identity;

import java.time.LocalDateTime;

public class Profile extends User {
    private String encryptionPrivateKey;
    private String identitySigningPrivateKey;
    private String accessToken;
    private LocalDateTime createdAt;

    // Initialize
    public Profile() {
        super();
    }

    public String getEncryptionPrivateKey() { return encryptionPrivateKey; }
    public void setEncryptionPrivateKey(String encryptionPrivateKey) { this.encryptionPrivateKey = encryptionPrivateKey; }
    public String getIdentitySigningPrivateKey() { return identitySigningPrivateKey; }
    public void setIdentitySigningPrivateKey(String identitySigningPrivateKey) { this.identitySigningPrivateKey = identitySigningPrivateKey; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
