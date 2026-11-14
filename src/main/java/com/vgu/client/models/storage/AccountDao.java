package com.vgu.client.models.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** The single row describing whose database this is, plus the sync cursor. */
public final class AccountDao extends BaseDao {

    /** user_id before the server has assigned one; real server ids are always positive. */
    public static final long LOCAL_USER_ID = 0;

    public record Account(long userId, String username, long syncCursor) {
    }

    public AccountDao(ChatDatabase db) {
        super(db);
    }

    public void create(String username, long userId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO account (id, user_id, username) VALUES (1, ?, ?)")) {
                ps.setLong(1, userId);
                ps.setString(2, username);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Takes over the real server user id (first server login) and re-labels messages written as "local". */
    public void bindUserId(long userId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE account SET user_id = ? WHERE id = 1")) {
                ps.setLong(1, userId);
                ps.executeUpdate();
            }
            // Everything written while this account was "local" (user id 0) now belongs to the real id.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE messages SET sender_id = ? WHERE sender_id = ?")) {
                ps.setLong(1, userId);
                ps.setLong(2, LOCAL_USER_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE group_sender_keys SET sender_id = ? WHERE sender_id = ?")) {
                ps.setLong(1, userId);
                ps.setLong(2, LOCAL_USER_ID);
                ps.executeUpdate();
            }
            // Our own contact row and memberships: swap the id without breaking the foreign keys.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE contacts SET username = username || '#local' WHERE user_id = ?")) {
                ps.setLong(1, LOCAL_USER_ID);
                if (ps.executeUpdate() > 0) {
                    try (PreparedStatement copy = c.prepareStatement("""
                            INSERT OR REPLACE INTO contacts (user_id, username, avatar_local_path, encryption_public_key,
                                                             identity_signing_public_key, updated_at)
                            SELECT ?, REPLACE(username, '#local', ''), avatar_local_path, encryption_public_key,
                                   identity_signing_public_key, updated_at
                            FROM contacts WHERE user_id = ?
                            """)) {
                        copy.setLong(1, userId);
                        copy.setLong(2, LOCAL_USER_ID);
                        copy.executeUpdate();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE OR REPLACE conversation_members SET user_id = ? WHERE user_id = ?")) {
                ps.setLong(1, userId);
                ps.setLong(2, LOCAL_USER_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM contacts WHERE user_id = ?")) {
                ps.setLong(1, LOCAL_USER_ID);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Moves the sync cursor forward (never back). */
    public void advanceCursor(long serverMsgId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE account SET sync_cursor = MAX(sync_cursor, ?) WHERE id = 1")) {
                ps.setLong(1, serverMsgId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public record Avatar(byte[] image, Long fileId, String key) {
    }

    public void saveAvatar(byte[] image, long fileId, String key) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE account SET avatar_image = ?, avatar_file_id = ?, avatar_key = ? WHERE id = 1")) {
                ps.setBytes(1, image);
                ps.setLong(2, fileId);
                ps.setString(3, key);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** The saved avatar, or null if none. */
    public Avatar avatar() {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT avatar_image, avatar_file_id, avatar_key FROM account WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getBytes(1) == null) {
                    return null;
                }
                long fileId = rs.getLong(2);
                return new Avatar(rs.getBytes(1), rs.wasNull() ? null : fileId, rs.getString(3));
            }
        });
    }

    public Account get() {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT user_id, username, sync_cursor FROM account WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new DatabaseException("Account row is missing", null);
                }
                return new Account(rs.getLong(1), rs.getString(2), rs.getLong(3));
            }
        });
    }
}
