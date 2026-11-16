package com.vgu.client.models.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ContactDao extends BaseDao {

    public ContactDao(ChatDatabase db) {
        super(db);
    }

    /** Inserts the contact, or refreshes its username and keys if it already exists. */
    public void upsert(long userId, String username, String encryptionPublicKey, String identitySigningPublicKey) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO contacts (user_id, username, encryption_public_key, identity_signing_public_key, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(user_id) DO UPDATE SET
                        username = excluded.username,
                        encryption_public_key = excluded.encryption_public_key,
                        identity_signing_public_key = excluded.identity_signing_public_key,
                        updated_at = excluded.updated_at
                    """)) {
                ps.setLong(1, userId);
                ps.setString(2, username);
                ps.setString(3, encryptionPublicKey);
                ps.setString(4, identitySigningPublicKey);
                ps.setString(5, now());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Makes sure a contact row exists. The server only gives user ids for members, so until a username
     * is known the contact is stored as "user#&lt;id&gt;". Real usernames cannot contain '#', so this can never
     * collide with somebody's actual (unique) username; {@link #rename} and {@link #upsert} fix it later.
     */
    public void ensure(long userId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO contacts (user_id, username, updated_at) VALUES (?, 'user#' || ?, ?)")) {
                ps.setLong(1, userId);
                ps.setLong(2, userId);
                ps.setString(3, now());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public record Keys(String encryptionPublicKey, String identitySigningPublicKey) {
    }

    /** A member of a conversation as the member list shows it. */
    public record Member(long userId, String username) {
        public Member() {
            this(0, "");
        }

        public String handle() {
            return "@" + username;
        }
    }

    /** The public keys we already know for a user, or null. */
    public Keys keys(long userId) {
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT encryption_public_key, identity_signing_public_key FROM contacts WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getString(1) == null || rs.getString(2) == null) {
                        return null;
                    }
                    return new Keys(rs.getString(1), rs.getString(2));
                }
            }
        });
    }

    public void saveKeys(long userId, String encryptionPublicKey, String identitySigningPublicKey) {
        ensure(userId);
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE contacts SET encryption_public_key = ?, identity_signing_public_key = ?, updated_at = ?
                    WHERE user_id = ?
                    """)) {
                ps.setString(1, encryptionPublicKey);
                ps.setString(2, identitySigningPublicKey);
                ps.setString(3, now());
                ps.setLong(4, userId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Sets the real username of a contact (learned from a signed key package). Ignored if it is already taken. */
    public void rename(long userId, String username) {
        ensure(userId);
        try {
            db.write(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE contacts SET username = ? WHERE user_id = ?")) {
                    ps.setString(1, username);
                    ps.setLong(2, userId);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (DatabaseException ignored) {
            // username UNIQUE constraint: keep the placeholder rather than failing the message
        }
    }

    /** Members of a conversation except {@code excludeUserId} (usually the account owner). */
    public List<Member> listMembers(long conversationId, long excludeUserId) {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT ct.user_id, ct.username
                    FROM conversation_members m
                    JOIN contacts ct ON ct.user_id = m.user_id
                    WHERE m.conversation_id = ? AND m.user_id <> ?
                    ORDER BY ct.username
                    """)) {
                ps.setLong(1, conversationId);
                ps.setLong(2, excludeUserId);
                List<Member> members = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        members.add(new Member(rs.getLong(1), rs.getString(2)));
                    }
                }
                return members;
            }
        });
    }
}
