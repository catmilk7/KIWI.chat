package com.vgu.client.models.messaging;

import com.vgu.client.enums.MessageType;

/** A plain text message. */
public final class TextContent extends MessageContent {

    private final String text;

    /** An empty message. */
    public TextContent() {
        this("");
    }

    public TextContent(String text) {
        this.text = text == null ? "" : text;
    }

    @Override
    public MessageType type() {
        return MessageType.TEXT;
    }

    @Override
    public String preview() {
        return text.replace('\n', ' ');
    }

    @Override
    public String bubbleText() {
        return text;
    }

    @Override
    public String toStorage() {
        return text;
    }

    @Override
    public String toString() {
        return "TextContent[" + preview() + "]";
    }
}
