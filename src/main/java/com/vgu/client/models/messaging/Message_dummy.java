package com.vgu.client.models.messaging;

import com.vgu.client.models.identity.User_dummy;
import java.time.LocalDateTime;

public class Message_dummy {
    private String id;
    private User_dummy sender;
    private String content;
    private LocalDateTime timestamp;

    public Message_dummy(String id, User_dummy sender, String content, LocalDateTime timestamp) {
        this.id = id;
        this.sender = sender;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getId() { return id; }
    public User_dummy getSender() { return sender; }
    public String getContent() { return content; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
