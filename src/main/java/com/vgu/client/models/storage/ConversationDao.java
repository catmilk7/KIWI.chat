package com.vgu.client.models.storage;

import com.vgu.client.enums.ConversationType;
import com.vgu.client.enums.MessageType;
import com.vgu.client.models.messaging.ConversationItem;
import com.vgu.client.models.messaging.MessageContent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ConversationDao extends BaseDao {

    private final long me;

    /** @param me the account owner's user id, used to find "the other person" in direct chats */
    public ConversationDao(ChatDatabase db, long me) {
        super(db);
        this.me = me;
    }

    public void upsert(long id, ConversationType type, String title, Long directUserLow, Long directUserHigh) {
        upsert(id, type, title, directUserLow, directUserHigh, 0);
    }

    /**
     * Inserts a conversation (as the server describes it) or refreshes it. A null title keeps
     * the title we already know; the epoch only ever moves forward.
     */
    public void upsert(long id, ConversationType type, String title, Long directUserLow, Long directUserHigh,
                       long epoch) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO conversations (conversation_id, type, title, direct_user_low, direct_user_high, epoch, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(conversation_id) DO UPDATE SET
                        title = COALESCE(excluded.title, title),
                        epoch = MAX(epoch, excluded.epoch)
                    """)) {
                ps.setLong(1, id);
                ps.setString(2, type.name());
                ps.setString(3, title);
                ps.setObject(4, directUserLow);
                ps.setObject(5, directUserHigh);
                ps.setLong(6, epoch);
                ps.setString(7, now());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** An invite code that has not expired yet. */
    public record Invite(String code, Instant expiresAt) {
    }

    public void saveInvite(long conversationId, String code, Instant expiresAt) {
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE conversations SET active_invite_code = ?, invite_expires_at = ? WHERE conversation_id = ?")) {
                ps.setString(1, code);
                ps.setString(2, expiresAt.toString());
                ps.setLong(3, conversationId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Forgets the stored invite code (it expired or was revoked). */
    public void clearInvite(long conversationId) {
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE conversations SET active_invite_code = NULL, invite_expires_at = NULL WHERE conversation_id = ?")) {
                ps.setLong(1, conversationId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * The stored invite code, or null if there is none. A code that has expired is removed from
     * the row here, so an expired code is never returned and never lingers in the database.
     */
    public Invite activeInvite(long conversationId) {
        return activeInvite(conversationId, Instant.now());
    }

    /** As above, judging expiry against {@code now}: pass the server's time, not the local clock. */
    public Invite activeInvite(long conversationId, Instant now) {
        Invite stored = db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT active_invite_code, invite_expires_at FROM conversations WHERE conversation_id = ?")) {
                ps.setLong(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getString(1) == null || rs.getString(2) == null) {
                        return null;
                    }
                    return new Invite(rs.getString(1), Instant.parse(rs.getString(2)));
                }
            }
        });
        if (stored != null && !stored.expiresAt().isAfter(now)) {
            clearInvite(conversationId);
            return null;
        }
        return stored;
    }

    /** Drops members the server no longer lists (they left or were kicked). */
    public void retainMembers(long conversationId, java.util.Collection<Long> memberIds) {
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id FROM conversation_members WHERE conversation_id = ?")) {
                ps.setLong(1, conversationId);
                List<Long> stale = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!memberIds.contains(rs.getLong(1))) {
                            stale.add(rs.getLong(1));
                        }
                    }
                }
                for (long userId : stale) {
                    try (PreparedStatement delete = conn.prepareStatement(
                            "DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?")) {
                        delete.setLong(1, conversationId);
                        delete.setLong(2, userId);
                        delete.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    public void removeMember(long conversationId, long userId) {
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?")) {
                ps.setLong(1, conversationId);
                ps.setLong(2, userId);
                ps.executeUpdate();
            }
            pruneOrphanContacts(conn);
            return null;
        });
    }

    /**
     * Deletes every local conversation whose id is not in {@code keep}: the server is the truth
     * about membership. Returns how many were removed.
     */
    public int deleteAllExcept(java.util.Collection<Long> keep) {
        int removed = db.write(conn -> {
            List<Long> gone = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT conversation_id FROM conversations");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!keep.contains(rs.getLong(1))) {
                        gone.add(rs.getLong(1));
                    }
                }
            }
            for (long id : gone) {
                forget(conn, id);
            }
            pruneOrphanContacts(conn);
            return gone.size();
        });
        if (removed > 0) {
            db.checkpoint();
        }
        return removed;
    }

    /**
     * Forgets a conversation completely: its messages, attachments, members and sender keys
     * (by cascade), and the contacts that were only known through it.
     */
    public void delete(long conversationId) {
        db.write(conn -> {
            forget(conn, conversationId);
            pruneOrphanContacts(conn);
            return null;
        });
        db.checkpoint();
    }

    private static void forget(java.sql.Connection conn, long conversationId) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM conversations WHERE conversation_id = ?")) {
            ps.setLong(1, conversationId);
            ps.executeUpdate();
        }
    }

    /** Removes contacts nothing refers to any more: not a member anywhere, no message, not a direct-chat peer, not us. */
    private void pruneOrphanContacts(java.sql.Connection conn) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM contacts
                WHERE user_id <> ?
                  AND user_id NOT IN (SELECT user_id FROM conversation_members)
                  AND user_id NOT IN (SELECT sender_id FROM messages)
                  AND user_id NOT IN (SELECT direct_user_low FROM conversations WHERE direct_user_low IS NOT NULL)
                  AND user_id NOT IN (SELECT direct_user_high FROM conversations WHERE direct_user_high IS NOT NULL)
                """)) {
            ps.setLong(1, me);
            ps.executeUpdate();
        }
    }

    public String title(long conversationId) {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT title FROM conversations WHERE conversation_id = ?")) {
                ps.setLong(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** Sets the title only if we do not know one yet. */
    public void setTitleIfUnknown(long conversationId, String title) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE conversations SET title = ? WHERE conversation_id = ? AND title IS NULL")) {
                ps.setString(1, title);
                ps.setLong(2, conversationId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public boolean exists(long conversationId) {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM conversations WHERE conversation_id = ?")) {
                ps.setLong(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** "OWNER" or "MEMBER" for the account owner in this conversation, or null if not a member. */
    public String myRole(long conversationId) {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?")) {
                ps.setLong(1, conversationId);
                ps.setLong(2, me);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    public void addMember(long conversationId, long userId, String role) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(conversation_id, user_id) DO UPDATE SET role = excluded.role
                    """)) {
                ps.setLong(1, conversationId);
                ps.setLong(2, userId);
                ps.setString(3, role);
                ps.setString(4, now());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Conversation list, newest activity first; {@code query} filters by title (blank = all). */
    public List<ConversationItem> list(String query) {
        String pattern = "%" + escapeLike(query == null ? "" : query.trim()) + "%";
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT c.conversation_id, c.type, c.unread_count, c.updated_at,
                           COALESCE(c.title, '@' || peer.username,
                                    CASE WHEN c.type = 'GROUP' THEN 'Group #' || c.conversation_id ELSE 'Unknown' END) AS shown_title,
                           lm.plaintext_content, lm.sender_id, sender.username, lm.msg_type
                    FROM conversations c
                    LEFT JOIN contacts peer
                           ON c.type = 'DIRECT'
                          AND peer.user_id = CASE WHEN c.direct_user_low = ? THEN c.direct_user_high ELSE c.direct_user_low END
                    LEFT JOIN messages lm
                           ON lm.local_id = (SELECT m.local_id FROM messages m WHERE m.conversation_id = c.conversation_id
                                             ORDER BY (m.server_msg_id IS NULL) DESC, m.server_msg_id DESC, m.local_id DESC LIMIT 1)
                    LEFT JOIN contacts sender ON sender.user_id = lm.sender_id
                    WHERE shown_title LIKE ? ESCAPE '\\'
                    ORDER BY c.updated_at DESC, c.conversation_id DESC
                    """)) {
                ps.setLong(1, me);
                ps.setString(2, pattern);
                List<ConversationItem> items = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ConversationItem.LastMessage last = rs.getString(9) == null ? null
                                : new ConversationItem.LastMessage(
                                        MessageContent.parse(MessageType.valueOf(rs.getString(9)), rs.getString(6)),
                                        rs.getLong(7) == me, rs.getString(8));
                        items.add(ConversationItem.of(ConversationType.valueOf(rs.getString(2)), rs.getLong(1),
                                rs.getString(5), rs.getInt(3), Instant.parse(rs.getString(4)), last));
                    }
                }
                return items;
            }
        });
    }

    public void markRead(long conversationId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE conversations SET unread_count = 0 WHERE conversation_id = ? AND unread_count <> 0")) {
                ps.setLong(1, conversationId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
