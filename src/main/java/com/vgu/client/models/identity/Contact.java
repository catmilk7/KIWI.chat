package com.vgu.client.models.identity;

import java.time.LocalDateTime;

public class Contact extends User {
    private String avatarLocalPath;
    private String encryptionPublicKey;
    private String identitySigningPublicKey;
    private LocalDateTime updatedAt;

    // Initialize
    public Contact() {
        super();
    }

    public String getAvatarLocalPath() { return avatarLocalPath; }
    public void setAvatarLocalPath(String avatarLocalPath) { this.avatarLocalPath = avatarLocalPath; }
    public String getEncryptionPublicKey() { return encryptionPublicKey; }
    public void setEncryptionPublicKey(String encryptionPublicKey) { this.encryptionPublicKey = encryptionPublicKey; }
    public String getIdentitySigningPublicKey() { return identitySigningPublicKey; }
    public void setIdentitySigningPublicKey(String identitySigningPublicKey) { this.identitySigningPublicKey = identitySigningPublicKey; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
