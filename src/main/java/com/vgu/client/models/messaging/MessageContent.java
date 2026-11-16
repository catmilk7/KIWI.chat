package com.vgu.client.models.messaging;

import com.vgu.client.enums.MessageType;

import java.util.Optional;

/**
 * What a chat message says, independent of how it is stored or drawn. The kinds of content
 * (plain text, an attachment) answer the same questions in their own way, so the rest of the
 * program asks the question and never checks which kind it has: {@link #preview()} for the
 * conversation list, {@link #bubbleText()} for the chat bubble, {@link #toStorage()} for the
 * database and the wire.
 */
public abstract class MessageContent {

    public abstract MessageType type();

    /** One short line for the conversation list. */
    public abstract String preview();

    /** The text shown inside the chat bubble. */
    public abstract String bubbleText();

    /** The string kept in the database (and, encrypted, sent to the server). */
    public abstract String toStorage();

    /** The file this message carries, if any. Content without a file keeps this default. */
    public Optional<FileAttachment> attachment() {
        return Optional.empty();
    }

    /** Rebuilds the right kind of content from what {@link #toStorage()} produced. */
    public static MessageContent parse(MessageType type, String stored) {
        if (type == MessageType.FILE_KEY) {
            FileAttachment file = FileAttachment.fromJson(stored);
            if (file != null) {
                return new AttachmentContent(file);
            }
        }
        return new TextContent(stored == null ? "" : stored);
    }
}
