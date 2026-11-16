package com.vgu.client.models.messaging;

import com.vgu.client.enums.ConversationType;

import java.time.Instant;

/** A conversation between two people. */
public final class DirectConversationItem extends ConversationItem {

    /** An empty DirectConversationItem. */
    public DirectConversationItem() {
        super();
    }

    /** A new, empty DirectConversationItem with just an id and a title. */
    public DirectConversationItem(long id, String title) {
        super(id, title);
    }

    public DirectConversationItem(long id, String title, int unreadCount, Instant updatedAt, LastMessage last) {
        super(id, title, unreadCount, updatedAt, last);
    }

    @Override
    public ConversationType type() {
        return ConversationType.DIRECT;
    }

    @Override
    public String avatarResource() {
        return "/com/vgu/client/ui/assets/default/anon_sender.png";
    }

    @Override
    public String statusText() {
        return "Direct message";
    }

    @Override
    public boolean hasSettings() {
        return false;
    }

    @Override
    public String leaveLabel(boolean owner) {
        return null; // the server has no way to leave a direct chat
    }
}
