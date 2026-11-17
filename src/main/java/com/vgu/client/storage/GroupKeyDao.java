package com.vgu.client.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;

/** Sender keys of group conversations (secret AES keys, Base64). Safe at rest: the whole database is encrypted. */
public final class GroupKeyDao extends BaseDao {

    /** A stored key and, for our own keys, whether every member has received it yet. */
    public static final class Entry {
        private final String keyBase64;
        private final boolean distributed;

        public Entry(String keyBase64, boolean distributed) {
            this.keyBase64 = keyBase64;
            this.distributed = distributed;
        }

        public String getKeyBase64() {
            return keyBase64;
        }

        public boolean isDistributed() {
            return distributed;
        }
    }

    /** The group meta key (the key that seals the group name) is stored under these reserved coordinates. */
    private static final long META_SENDER = 0;
    private static final long META_EPOCH = -1;

    public GroupKeyDao(ChatDatabase db) {
        super(db);
    }

    /** Stores the key that seals this group's name, replacing any earlier one. */
    public void saveMeta(long conversationId, String keyBase64) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO group_sender_keys (conversation_id, sender_id, epoch, symmetric_key, distributed, created_at) "
                    + "VALUES (?, ?, ?, ?, 1, ?) "
                    + "ON CONFLICT(conversation_id, sender_id, epoch) DO UPDATE SET symmetric_key = excluded.symmetric_key")) {
                ps.setLong(1, conversationId);
                ps.setLong(2, META_SENDER);
                ps.setLong(3, META_EPOCH);
                ps.setString(4, keyBase64);
                ps.setString(5, now());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<String> findMeta(long conversationId) {
        return find(conversationId, META_SENDER, META_EPOCH);
    }

    /** Forgets that our keys, in every conversation, were handed out, so the next announce sends them (and what they carry) again. */
    public void markAllUndistributed(long senderId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE group_sender_keys SET distributed = 0 WHERE sender_id = ? AND epoch >= 0")) {
                ps.setLong(1, senderId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Forgets that our own keys in this conversation were handed out, so the next announce sends them again. */
    public void markUndistributed(long conversationId, long senderId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE group_sender_keys SET distributed = 0 "
                    + "WHERE conversation_id = ? AND sender_id = ? AND epoch >= 0")) {
                ps.setLong(1, conversationId);
                ps.setLong(2, senderId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Stores a key unless one already exists for this (conversation, sender, epoch). */
    public void save(long conversationId, long senderId, long epoch, String keyBase64, boolean distributed) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO group_sender_keys (conversation_id, sender_id, epoch, symmetric_key, distributed, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT DO NOTHING ")) {
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
            try (PreparedStatement ps = c.prepareStatement("UPDATE group_sender_keys SET distributed = 1 "
                    + "WHERE conversation_id = ? AND sender_id = ? AND epoch = ? ")) {
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
            try (PreparedStatement ps = c.prepareStatement("SELECT symmetric_key, distributed FROM group_sender_keys "
                    + "WHERE conversation_id = ? AND sender_id = ? AND epoch = ? ")) {
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
        return findEntry(conversationId, senderId, epoch).map(entry -> entry.getKeyBase64());
    }
}
