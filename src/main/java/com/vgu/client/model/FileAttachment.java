package com.vgu.client.model;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * What a FILE_KEY message says about its file: where the encrypted blob is on the server and the
 * key to open it. Stored as the message content; a secret, kept only in the encrypted database.
 */
public final class FileAttachment {
    private final long fileId;
    private final String key;
    private final String name;
    private final long size;

    public FileAttachment(long fileId, String key, String name, long size) {
        this.fileId = fileId;
        this.key = key;
        this.name = name;
        this.size = size;
    }

    public long getFileId() {
        return fileId;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public long getSize() {
        return size;
    }


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
            return parsed != null && parsed.getKey() != null ? parsed : null;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /** Two attachments are equal when all four facts are equal (lecture 5: overriding equals). */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FileAttachment)) return false;
        FileAttachment that = (FileAttachment) other;
        return fileId == that.fileId && size == that.size
                && java.util.Objects.equals(key, that.key) && java.util.Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fileId, key, name, size);
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
