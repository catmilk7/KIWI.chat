package com.vgu.client.models.messaging;

import com.vgu.client.enums.DeliveryStatus;
import com.vgu.client.enums.MessageType;

import java.time.Instant;

/**
 * One chat message as stored locally. {@code serverMsgId} is null until the
 * server has acknowledged it. {@code senderUsername} comes from the contacts
 * table and is null for the account owner's own messages.
 */
public record Message(
        long localId,
        Long serverMsgId,
        String clientMsgId,
        long conversationId,
        long senderId,
        String senderUsername,
        MessageType type,
        String content,
        DeliveryStatus status,
        Instant createdAt) {

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
