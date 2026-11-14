package com.vgu.client.models.network;

import java.util.List;

/** Shapes of the server's JSON {@code data} payloads (see KIWI.Server docs/api). Times are ISO-8601 strings. */
public final class Dto {

    private Dto() {
    }

    public record Auth(long userId, String username, String token, String tokenType, String expiresAt) {
    }

    public record KeyBundle(long userId, String encryptionPublicKey, String identitySigningPublicKey, String updatedAt) {
    }

    public record Conversation(long conversationId, String type, String encryptedMeta, String createdAt,
                               List<Long> memberIds, String myRole, long epoch, long lastMsgId) {
    }

    public record Invite(long inviteId, long conversationId, String code, String expiresAt) {
    }

    public record DeliveryToken(String token, String tokenType, String expiresAt) {
    }

    public record FileUpload(long fileId, long size) {
    }

    public record SendResult(long messageId, String clientMsgId, String createdAt, boolean duplicate) {
    }

    public record SyncMessage(long messageId, long sequence, long conversationId, long senderId, String type,
                              Long recipientId, Long epoch, String ciphertext, String iv, String clientMsgId,
                              String createdAt) {
    }

    public record MessagePage(List<SyncMessage> messages, long nextCursor, boolean hasMore) {
    }
}
