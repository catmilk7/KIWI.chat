package com.vgu.client.models.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;

/** Sender keys of group conversations (secret AES keys, Base64). Safe at rest: the whole database is encrypted. */
public final class GroupKeyDao extends BaseDao {

    /** A stored key and, for our own keys, whether every member has received it yet. */
    public record Entry(String keyBase64, boolean distributed) {
    }

    public GroupKeyDao(ChatDatabase db) {
        super(db);
    }

    /** Stores a key unless one already exists for this (conversation, sender, epoch). */
    public void save(long conversationId, long senderId, long epoch, String keyBase64, boolean distributed) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO group_sender_keys (conversation_id, sender_id, epoch, symmetric_key, distributed, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """)) {
                ps.setLong(1, conversationId);
                ps.setLong(2, senderId);
                ps.setLong(3, epoch);
                ps.setString(4, keyBase64);
                ps.setInt(5, distributed ? 1 : 0);
                ps.setString(6, now());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void save(long conversationId, long senderId, long epoch, String keyBase64) {
        save(conversationId, senderId, epoch, keyBase64, false);
    }

    public void markDistributed(long conversationId, long senderId, long epoch) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE group_sender_keys SET distributed = 1
                    WHERE conversation_id = ? AND sender_id = ? AND epoch = ?
                    """)) {
                ps.setLong(1, conversationId);
                ps.setLong(2, senderId);
                ps.setLong(3, epoch);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Entry> findEntry(long conversationId, long senderId, long epoch) {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT symmetric_key, distributed FROM group_sender_keys
                    WHERE conversation_id = ? AND sender_id = ? AND epoch = ?
                    """)) {
                ps.setLong(1, conversationId);
                ps.setLong(2, senderId);
                ps.setLong(3, epoch);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new Entry(rs.getString(1), rs.getInt(2) == 1)) : Optional.empty();
                }
            }
        });
    }

    public Optional<String> find(long conversationId, long senderId, long epoch) {
        return findEntry(conversationId, senderId, epoch).map(Entry::keyBase64);
    }
}
