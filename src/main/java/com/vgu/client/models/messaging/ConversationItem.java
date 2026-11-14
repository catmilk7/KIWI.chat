package com.vgu.client.models.messaging;

import com.vgu.client.enums.ConversationType;

import java.time.Instant;

/**
 * One row of the conversation list. A direct chat and a group differ in how they look and what
 * they allow, and those differences live in the two subclasses instead of in isGroup() checks
 * spread over the screens: the screen asks {@link #avatarResource()}, {@link #hasSettings()},
 * {@link #leaveLabel(boolean)} and gets the right answer for whichever kind it holds.
 */
public abstract class ConversationItem {

    /** The newest message of a conversation, as the list needs it. */
    public record LastMessage(MessageContent content, boolean mine, String senderUsername) {
    }

    private final long id;
    private final String title;
    private final int unreadCount;
    private final Instant updatedAt;
    private final LastMessage last;

    /** An empty item: id 0, no title, nothing unread, no messages. */
    protected ConversationItem() {
        this(0, "", 0, Instant.EPOCH, null);
    }

    /** An item with only the facts every conversation has; nothing unread and no messages yet. */
    protected ConversationItem(long id, String title) {
        this(id, title, 0, Instant.EPOCH, null);
    }

    protected ConversationItem(long id, String title, int unreadCount, Instant updatedAt, LastMessage last) {
        this.id = id;
        this.title = title;
        this.unreadCount = unreadCount;
        this.updatedAt = updatedAt;
        this.last = last;
    }

    /** Creates the subclass that matches {@code type}. */
    public static ConversationItem of(ConversationType type, long id, String title, int unreadCount, Instant updatedAt,
                                      LastMessage last) {
        return switch (type) {
            case DIRECT -> new DirectConversationItem(id, title, unreadCount, updatedAt, last);
            case GROUP -> new GroupConversationItem(id, title, unreadCount, updatedAt, last);
        };
    }

    public long id() { return id; }
    public String title() { return title; }
    public int unreadCount() { return unreadCount; }
    public Instant updatedAt() { return updatedAt; }

    public boolean isGroup() {
        return type() == ConversationType.GROUP;
    }

    /** "You: hi", "@alice: hi" or "hi", depending on who wrote it and what kind of conversation this is. */
    public String lastPreview() {
        if (last == null) {
            return "No messages yet";
        }
        String text = last.content().preview();
        return last.mine() ? "You: " + text : previewOf(text, last.senderUsername());
    }

    /** How somebody else's message reads in the list. Direct chats keep this default; groups name the sender. */
    protected String previewOf(String text, String senderUsername) {
        return text;
    }

    public abstract ConversationType type();

    /** Classpath location of the default avatar. */
    public abstract String avatarResource();

    /** The line under the name in the chat header. */
    public abstract String statusText();

    /** Whether there is a settings window (members, invite code) for this conversation. */
    public abstract boolean hasSettings();

    /** The text of the menu entry that ends the conversation, or null if it cannot be left. */
    public abstract String leaveLabel(boolean owner);

    @Override
    public String toString() {
        return type() + "#" + id + " " + title;
    }
}
