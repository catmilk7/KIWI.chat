package com.vgu.client.models.messaging;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * What a FILE_KEY message says about its file: where the encrypted blob is on the server and the
 * key to open it. Stored as the message content; a secret, kept only in the encrypted database.
 */
public record FileAttachment(long fileId, String key, String name, long size) {

    private static final Gson GSON = new Gson();

    /** A description that points at nothing yet. */
    public FileAttachment() {
        this(0, "", "", 0);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Returns null if {@code json} is not an attachment description. */
    public static FileAttachment fromJson(String json) {
        try {
            FileAttachment parsed = GSON.fromJson(json, FileAttachment.class);
            return parsed != null && parsed.key() != null ? parsed : null;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    public String label() {
        return "\uD83D\uDCCE " + name + " (" + humanSize(size) + ")";
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
