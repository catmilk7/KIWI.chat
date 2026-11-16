package com.vgu.client.models.messaging;

import com.vgu.client.enums.MessageType;

import java.util.Optional;

/** A file message: it carries where the encrypted file is and the key to open it, never the file itself. */
public final class AttachmentContent extends MessageContent {

    private final FileAttachment file;

    /** An attachment that points at nothing yet (file id 0, no name). */
    public AttachmentContent() {
        this(new FileAttachment());
    }

    public AttachmentContent(FileAttachment file) {
        this.file = file;
    }

    /** Builds the file description from its parts. */
    public AttachmentContent(long fileId, String key, String name, long size) {
        this(new FileAttachment(fileId, key, name, size));
    }

    @Override
    public MessageType type() {
        return MessageType.FILE_KEY;
    }

    @Override
    public String preview() {
        return "📎 Attachment";
    }

    @Override
    public String bubbleText() {
        return file.label();
    }

    @Override
    public String toStorage() {
        return file.toJson();
    }

    @Override
    public Optional<FileAttachment> attachment() {
        return Optional.of(file);
    }

    @Override
    public String toString() {
        return "AttachmentContent[" + file.name() + "]";
    }
}
