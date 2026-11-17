package com.vgu.client.models.messaging;

import com.vgu.client.enums.DeliveryStatus;
import com.vgu.client.enums.MessageType;

import java.time.Instant;

/**
 * One chat message as stored locally. {@code serverMsgId} is null until the
 * server has acknowledged it. {@code senderUsername} comes from the contacts
 * table and is null for the account owner's own messages.
 */
public final class Message {
    private final long localId;
    private final Long serverMsgId;
    private final String clientMsgId;
    private final long conversationId;
    private final long senderId;
    private final String senderUsername;
    private final MessageType type;
    private final String content;
    private final DeliveryStatus status;
    private final Instant createdAt;

    public Message(long localId, Long serverMsgId, String clientMsgId, long conversationId, long senderId, String senderUsername, MessageType type, String content, DeliveryStatus status, Instant createdAt) {
        this.localId = localId;
        this.serverMsgId = serverMsgId;
        this.clientMsgId = clientMsgId;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.senderUsername = senderUsername;
        this.type = type;
        this.content = content;
        this.status = status;
        this.createdAt = createdAt;
    }

    public long getLocalId() {
        return localId;
    }

    public Long getServerMsgId() {
        return serverMsgId;
    }

    public String getClientMsgId() {
        return clientMsgId;
    }

    public long getConversationId() {
        return conversationId;
    }

    public long getSenderId() {
        return senderId;
    }

    public String getSenderUsername() {
        return senderUsername;
    }

    public MessageType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }


    /** A blank message: not yet sent, no text, created now. */
    public Message() {
        this(0, null, "", 0, 0, null, MessageType.TEXT, "", DeliveryStatus.SENDING, Instant.now());
    }

    /** A message the user has just written: not on the server yet, so no server id and no sender name. */
    public Message(long localId, String clientMsgId, long conversationId, long senderId, MessageContent content) {
        this(localId, null, clientMsgId, conversationId, senderId, null, content.type(), content.toStorage(),
                DeliveryStatus.SENDING, Instant.now());
    }

    /** The content as an object that knows how to preview and draw itself. */
    public MessageContent body() {
        return MessageContent.parse(type, content);
    }
}
