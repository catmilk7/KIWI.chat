package com.vgu.client.network;

import java.util.List;

/** Shapes of the server's JSON {@code data} payloads (see KIWI.Server docs/api). Times are ISO-8601 strings. */
public final class Dto {

    private Dto() {
    }

    public static final class Auth {
        private final long userId;
        private final String username;
        private final String token;
        private final String tokenType;
        private final String expiresAt;

        public Auth(long userId, String username, String token, String tokenType, String expiresAt) {
            this.userId = userId;
            this.username = username;
            this.token = token;
            this.tokenType = tokenType;
            this.expiresAt = expiresAt;
        }

        public long getUserId() {
            return userId;
        }

        public String getUsername() {
            return username;
        }

        public String getToken() {
            return token;
        }

        public String getTokenType() {
            return tokenType;
        }

        public String getExpiresAt() {
            return expiresAt;
        }
    }

    /** A user found by GET /api/users/search: who they are, nothing else. */
    public static final class UserSummary {
        @com.google.gson.annotations.SerializedName(value = "userId", alternate = {"id"})
        private final long userId;
        private final String username;

        public UserSummary(long userId, String username) {
            this.userId = userId;
            this.username = username;
        }

        public long getUserId() {
            return userId;
        }

        public String getUsername() {
            return username;
        }
    }

    public static final class KeyBundle {
        private final long userId;
        private final String encryptionPublicKey;
        private final String identitySigningPublicKey;
        private final String updatedAt;

        public KeyBundle(long userId, String encryptionPublicKey, String identitySigningPublicKey, String updatedAt) {
            this.userId = userId;
            this.encryptionPublicKey = encryptionPublicKey;
            this.identitySigningPublicKey = identitySigningPublicKey;
            this.updatedAt = updatedAt;
        }

        public long getUserId() {
            return userId;
        }

        public String getEncryptionPublicKey() {
            return encryptionPublicKey;
        }

        public String getIdentitySigningPublicKey() {
            return identitySigningPublicKey;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }
    }

    public static final class Conversation {
        private final long conversationId;
        private final String type;
        private final String encryptedMeta;
        private final String createdAt;
        private final List<Long> memberIds;
        private final String myRole;
        private final long epoch;
        private final long lastMsgId;

        public Conversation(long conversationId, String type, String encryptedMeta, String createdAt, List<Long> memberIds, String myRole, long epoch, long lastMsgId) {
            this.conversationId = conversationId;
            this.type = type;
            this.encryptedMeta = encryptedMeta;
            this.createdAt = createdAt;
            this.memberIds = memberIds;
            this.myRole = myRole;
            this.epoch = epoch;
            this.lastMsgId = lastMsgId;
        }

        public long getConversationId() {
            return conversationId;
        }

        public String getType() {
            return type;
        }

        public String getEncryptedMeta() {
            return encryptedMeta;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public List<Long> getMemberIds() {
            return memberIds;
        }

        public String getMyRole() {
            return myRole;
        }

        public long getEpoch() {
            return epoch;
        }

        public long getLastMsgId() {
            return lastMsgId;
        }
    }

    public static final class Invite {
        private final long inviteId;
        private final long conversationId;
        private final String code;
        private final String expiresAt;

        public Invite(long inviteId, long conversationId, String code, String expiresAt) {
            this.inviteId = inviteId;
            this.conversationId = conversationId;
            this.code = code;
            this.expiresAt = expiresAt;
        }

        public long getInviteId() {
            return inviteId;
        }

        public long getConversationId() {
            return conversationId;
        }

        public String getCode() {
            return code;
        }

        public String getExpiresAt() {
            return expiresAt;
        }
    }

    public static final class DeliveryToken {
        private final String token;
        private final String tokenType;
        private final String expiresAt;

        public DeliveryToken(String token, String tokenType, String expiresAt) {
            this.token = token;
            this.tokenType = tokenType;
            this.expiresAt = expiresAt;
        }

        public String getToken() {
            return token;
        }

        public String getTokenType() {
            return tokenType;
        }

        public String getExpiresAt() {
            return expiresAt;
        }
    }

    public static final class FileUpload {
        private final long fileId;
        private final long size;

        public FileUpload(long fileId, long size) {
            this.fileId = fileId;
            this.size = size;
        }

        public long getFileId() {
            return fileId;
        }

        public long getSize() {
            return size;
        }
    }

    public static final class SendResult {
        private final long messageId;
        private final String clientMsgId;
        private final String createdAt;
        private final boolean duplicate;

        public SendResult(long messageId, String clientMsgId, String createdAt, boolean duplicate) {
            this.messageId = messageId;
            this.clientMsgId = clientMsgId;
            this.createdAt = createdAt;
            this.duplicate = duplicate;
        }

        public long getMessageId() {
            return messageId;
        }

        public String getClientMsgId() {
            return clientMsgId;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public boolean isDuplicate() {
            return duplicate;
        }
    }

    public static final class SyncMessage {
        private final long messageId;
        private final long sequence;
        private final long conversationId;
        private final long senderId;
        private final String type;
        private final Long recipientId;
        private final Long epoch;
        private final String ciphertext;
        private final String iv;
        private final String clientMsgId;
        private final String createdAt;

        public SyncMessage(long messageId, long sequence, long conversationId, long senderId, String type, Long recipientId, Long epoch, String ciphertext, String iv, String clientMsgId, String createdAt) {
            this.messageId = messageId;
            this.sequence = sequence;
            this.conversationId = conversationId;
            this.senderId = senderId;
            this.type = type;
            this.recipientId = recipientId;
            this.epoch = epoch;
            this.ciphertext = ciphertext;
            this.iv = iv;
            this.clientMsgId = clientMsgId;
            this.createdAt = createdAt;
        }

        public long getMessageId() {
            return messageId;
        }

        public long getSequence() {
            return sequence;
        }

        public long getConversationId() {
            return conversationId;
        }

        public long getSenderId() {
            return senderId;
        }

        public String getType() {
            return type;
        }

        public Long getRecipientId() {
            return recipientId;
        }

        public Long getEpoch() {
            return epoch;
        }

        public String getCiphertext() {
            return ciphertext;
        }

        public String getIv() {
            return iv;
        }

        public String getClientMsgId() {
            return clientMsgId;
        }

        public String getCreatedAt() {
            return createdAt;
        }
    }

    public static final class MessagePage {
        private final List<SyncMessage> messages;
        private final long nextCursor;
        private final boolean hasMore;

        public MessagePage(List<SyncMessage> messages, long nextCursor, boolean hasMore) {
            this.messages = messages;
            this.nextCursor = nextCursor;
            this.hasMore = hasMore;
        }

        public List<SyncMessage> getMessages() {
            return messages;
        }

        public long getNextCursor() {
            return nextCursor;
        }

        public boolean hasMore() {
            return hasMore;
        }
    }
}
