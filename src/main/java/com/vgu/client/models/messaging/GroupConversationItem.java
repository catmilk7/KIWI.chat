package com.vgu.client.models.messaging;

import com.vgu.client.enums.ConversationType;

import java.time.Instant;

/** A group chat: several members, an owner, invite codes. */
public final class GroupConversationItem extends ConversationItem {

    /** An empty GroupConversationItem. */
    public GroupConversationItem() {
        super();
    }

    /** A new, empty GroupConversationItem with just an id and a title. */
    public GroupConversationItem(long id, String title) {
        super(id, title);
    }

    public GroupConversationItem(long id, String title, int unreadCount, Instant updatedAt, LastMessage last) {
        super(id, title, unreadCount, updatedAt, last);
    }

    @Override
    public ConversationType type() {
        return ConversationType.GROUP;
    }

    @Override
    public String avatarResource() {
        return "/com/vgu/client/ui/assets/default/group.png";
    }

    @Override
    public String statusText() {
        return "Group Chat";
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    /** An owner cannot leave while others remain (the server refuses), so for them it deletes the group. */
    @Override
    public String leaveLabel(boolean owner) {
        return owner ? "Delete group" : "Leave group";
    }

    @Override
    protected String previewOf(String text, String senderUsername) {
        return senderUsername == null ? text : "@" + senderUsername + ": " + text;
    }
}
