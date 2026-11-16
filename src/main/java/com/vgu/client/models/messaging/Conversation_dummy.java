package com.vgu.client.models.messaging;

import com.vgu.client.models.identity.User_dummy;
import java.util.ArrayList;
import java.util.List;

public class Conversation_dummy {
    private String id;
    private String title;
    private boolean isGroup;
    private int unreadCount;
    private User_dummy targetUser; // Dùng cho chat 1-1 để lấy thông tin Profile
    private List<Message_dummy> messages = new ArrayList<>();

    public Conversation_dummy(String id, String title, boolean isGroup, int unreadCount, User_dummy targetUser) {
        this.id = id;
        this.title = title;
        this.isGroup = isGroup;
        this.unreadCount = unreadCount;
        this.targetUser = targetUser;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public boolean isGroup() { return isGroup; }
    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }
    public User_dummy getTargetUser() { return targetUser; }
    public List<Message_dummy> getMessages() { return messages; }

    public void addMessage(Message_dummy msg) {
        this.messages.add(msg);
    }

    public String getLastMessagePreview() {
        if (messages.isEmpty()) return "No messages yet";
        Message_dummy last = messages.get(messages.size() - 1);
        return last.getSender().getDisplayName() + ": " + last.getContent();
    }
}
