package com.vgu.client.models.storage;

import com.vgu.client.enums.DeliveryStatus;
import com.vgu.client.enums.MessageType;
import com.vgu.client.models.messaging.Message;
import com.vgu.client.models.messaging.MessageContent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** All SQL for chat messages: the outbox (send) side and the inbox (receive) side. */
public final class MessageDao extends BaseDao {

    private static final String SELECT = """
            SELECT m.local_id, m.server_msg_id, m.client_msg_id, m.conversation_id, m.sender_id,
                   ct.username, m.msg_type, m.plaintext_content, m.delivery_status, m.created_at
            FROM messages m
            LEFT JOIN contacts ct ON ct.user_id = m.sender_id
            """;

    /** Sent and received messages by server id; unsent ones (no server id yet) last, by when they were written. */
    static final String ARRIVAL_ORDER = "(m.server_msg_id IS NULL), m.server_msg_id, m.local_id";

    private final long me;

    public MessageDao(ChatDatabase db, long me) {
        super(db);
        this.me = me;
    }

    /**
     * Messages of one conversation in the order the server gave them (its message ids are one global
     * sequence), followed by our own messages that are still waiting to be sent, in the order they were
     * written. Timestamps are not used for ordering: they come from different clocks (ours may run
     * behind the server's), which put our own newest message above messages that arrived before it.
     */
    public List<Message> list(long conversationId) {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    SELECT + "WHERE m.conversation_id = ? ORDER BY " + ARRIVAL_ORDER)) {
                ps.setLong(1, conversationId);
                return readAll(ps);
            }
        });
    }

    /** Stores a message the user just typed as SENDING, so the UI can show it before the network answers. */
    public Message insertOutgoing(long conversationId, String text) {
        return insertOutgoing(conversationId, MessageType.TEXT, text);
    }

    /** Stores any kind of content; the content says what type it is and how it is written down. */
    public Message insertOutgoing(long conversationId, MessageContent content) {
        return insertOutgoing(conversationId, content.type(), content.toStorage());
    }

    /** An attachment message: the content is the {@link com.vgu.client.models.messaging.FileAttachment} JSON. */
    public Message insertOutgoingFile(long conversationId, com.vgu.client.models.messaging.FileAttachment file) {
        Message message = insertOutgoing(conversationId, MessageType.FILE_KEY, file.toJson());
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO local_file_attachments (server_file_id, message_id, local_path, file_name, file_size,
                                                        download_status, created_at)
                    VALUES (?, ?, '', ?, ?, 'COMPLETED', ?)
                    """)) {
                ps.setLong(1, file.fileId());
                ps.setLong(2, message.localId());
                ps.setString(3, file.name());
                ps.setLong(4, file.size());
                ps.setString(5, now());
                ps.executeUpdate();
            }
            return null;
        });
        return message;
    }

    public Message insertOutgoing(long conversationId, MessageType type, String text) {
        String clientMsgId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        return db.write(c -> {
            long localId;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO messages (client_msg_id, conversation_id, sender_id, msg_type,
                                          plaintext_content, delivery_status, created_at)
                    VALUES (?, ?, ?, ?, ?, 'SENDING', ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, clientMsgId);
                ps.setLong(2, conversationId);
                ps.setLong(3, me);
                ps.setString(4, type.name());
                ps.setString(5, text);
                ps.setString(6, now.toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    localId = keys.getLong(1);
                }
            }
            touchConversation(c, conversationId, now);
            return new Message(localId, null, clientMsgId, conversationId, me, null,
                    type, text, DeliveryStatus.SENDING, now);
        });
    }

    /** The server accepted the message: remember its id. Idempotent. */
    public void markSent(String clientMsgId, long serverMsgId) {
        markSent(clientMsgId, serverMsgId, null);
    }

    /**
     * Same, and also takes the server's time for the message, so the time shown next to it agrees with
     * the other members' messages instead of our own clock.
     */
    public void markSent(String clientMsgId, long serverMsgId, Instant serverTime) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE messages SET server_msg_id = ?, delivery_status = 'SENT', ciphertext = NULL, iv = NULL,
                                        created_at = COALESCE(?, created_at)
                    WHERE client_msg_id = ? AND sender_id = ? AND server_msg_id IS NULL
                    """)) {
                ps.setLong(1, serverMsgId);
                ps.setString(2, serverTime == null ? null : serverTime.toString());
                ps.setString(3, clientMsgId);
                ps.setLong(4, me);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void markFailed(String clientMsgId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE messages SET delivery_status = 'FAILED'
                    WHERE client_msg_id = ? AND sender_id = ? AND server_msg_id IS NULL
                    """)) {
                ps.setString(1, clientMsgId);
                ps.setLong(2, me);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Own messages that still need to be (re)sent. Resending is safe: the server dedups by clientMsgId. */
    public List<Message> pendingOutgoing() {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    SELECT + "WHERE m.delivery_status IN ('SENDING', 'FAILED') ORDER BY m.local_id")) {
                return readAll(ps);
            }
        });
    }

    /**
     * Stores one message received from the server (push or sync) and advances the sync
     * cursor in the same transaction. Returns true if the message was new for the UI.
     * Our own message coming back is matched by clientMsgId and only gets its server id.
     */
    public boolean saveIncoming(long serverMsgId, String clientMsgId, long conversationId, long senderId,
                                MessageType type, String plaintext, Instant createdAt) {
        return db.write(c -> {
            boolean isNew;
            int echoed;
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE messages SET server_msg_id = ?, delivery_status = 'SENT', ciphertext = NULL, iv = NULL,
                                        created_at = ?
                    WHERE client_msg_id = ? AND sender_id = ? AND conversation_id = ? AND server_msg_id IS NULL
                    """)) {
                ps.setLong(1, serverMsgId);
                ps.setString(2, createdAt.toString());
                ps.setString(3, clientMsgId);
                ps.setLong(4, senderId);
                ps.setLong(5, conversationId);
                echoed = ps.executeUpdate();
            }
            if (echoed > 0) {
                isNew = false;
            } else {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO messages (server_msg_id, client_msg_id, conversation_id, sender_id, msg_type,
                                              plaintext_content, delivery_status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'SENT', ?)
                        ON CONFLICT DO NOTHING
                        """)) {
                    ps.setLong(1, serverMsgId);
                    ps.setString(2, clientMsgId);
                    ps.setLong(3, conversationId);
                    ps.setLong(4, senderId);
                    ps.setString(5, type.name());
                    ps.setString(6, plaintext);
                    ps.setString(7, createdAt.toString());
                    isNew = ps.executeUpdate() > 0;
                }
                if (isNew) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            UPDATE conversations
                            SET last_msg_id = MAX(last_msg_id, ?),
                                unread_count = unread_count + CASE WHEN ? = ? THEN 0 ELSE 1 END,
                                updated_at = ?
                            WHERE conversation_id = ?
                            """)) {
                        ps.setLong(1, serverMsgId);
                        ps.setLong(2, senderId);
                        ps.setLong(3, me);
                        ps.setString(4, createdAt.toString());
                        ps.setLong(5, conversationId);
                        ps.executeUpdate();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE account SET sync_cursor = MAX(sync_cursor, ?) WHERE id = 1")) {
                ps.setLong(1, serverMsgId);
                ps.executeUpdate();
            }
            return isNew;
        });
    }

    private void touchConversation(java.sql.Connection c, long conversationId, Instant now) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE conversations SET updated_at = ? WHERE conversation_id = ?")) {
            ps.setString(1, now.toString());
            ps.setLong(2, conversationId);
            ps.executeUpdate();
        }
    }

    private static List<Message> readAll(PreparedStatement ps) throws java.sql.SQLException {
        List<Message> messages = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long server = rs.getLong(2);
                messages.add(new Message(rs.getLong(1), rs.wasNull() ? null : server, rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getString(6), MessageType.valueOf(rs.getString(7)),
                        rs.getString(8), DeliveryStatus.valueOf(rs.getString(9)), Instant.parse(rs.getString(10))));
            }
        }
        return messages;
    }
}
