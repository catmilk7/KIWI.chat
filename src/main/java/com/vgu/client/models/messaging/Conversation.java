package com.vgu.client.models.messaging;

import com.vgu.client.enums.ConversationType;
import java.time.LocalDateTime;

public class Conversation {
    private long conversationId;
    private ConversationType type;
    private String title;
    private String encryptedMeta;
    private String avatarLocalPath;
    private Long directUserLow;
    private Long directUserHigh;
    private int epoch;
    private long lastMsgId;
    private int unreadCount;
    private String draftText;
    private String activeInviteCode;
    private LocalDateTime inviteExpiresAt;
    private LocalDateTime updatedAt;

    public Conversation() {}
    public Conversation(
            long conversationId,
            ConversationType type,
            String title,
            String encryptedMeta,
            String avatarLocalPath,
            Long directUserLow,
            Long directUserHigh,
            int epoch,
            long lastMsgId,
            int unreadCount,
            String draftText,
            String activeInviteCode,
            LocalDateTime inviteExpiresAt,
            LocalDateTime updatedAt
    ) {
        this.conversationId = conversationId;
        this.type = type;
        this.title = title;
        this.encryptedMeta = encryptedMeta;
        this.avatarLocalPath = avatarLocalPath;
        this.directUserLow = directUserLow;
        this.directUserHigh = directUserHigh;
        this.epoch = epoch;
        this.lastMsgId = lastMsgId;
        this.unreadCount = unreadCount;
        this.draftText = draftText;
        this.activeInviteCode = activeInviteCode;
        this.inviteExpiresAt = inviteExpiresAt;
        this.updatedAt = updatedAt;
    }

    public long getConversationId() { return conversationId; }
    public void setConversationId(long conversationId) { this.conversationId = conversationId; }
    public ConversationType getType() { return type; }
    public void setType(ConversationType type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getEncryptedMeta() { return encryptedMeta; }
    public void setEncryptedMeta(String encryptedMeta) { this.encryptedMeta = encryptedMeta; }
    public String getAvatarLocalPath() { return avatarLocalPath; }
    public void setAvatarLocalPath(String avatarLocalPath) { this.avatarLocalPath = avatarLocalPath; }
    public Long getDirectUserLow() { return directUserLow; }
    public void setDirectUserLow(Long directUserLow) { this.directUserLow = directUserLow; }
    public Long getDirectUserHigh() { return directUserHigh; }
    public void setDirectUserHigh(Long directUserHigh) { this.directUserHigh = directUserHigh; }
    public int getEpoch() { return epoch; }
    public void setEpoch(int epoch) { this.epoch = epoch; }
    public long getLastMsgId() { return lastMsgId; }
    public void setLastMsgId(long lastMsgId) { this.lastMsgId = lastMsgId; }
    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }
    public String getDraftText() { return draftText; }
    public void setDraftText(String draftText) { this.draftText = draftText; }
    public String getActiveInviteCode() { return activeInviteCode; }
    public void setActiveInviteCode(String activeInviteCode) { this.activeInviteCode = activeInviteCode; }
    public LocalDateTime getInviteExpiresAt() { return inviteExpiresAt; }
    public void setInviteExpiresAt(LocalDateTime inviteExpiresAt) { this.inviteExpiresAt = inviteExpiresAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
