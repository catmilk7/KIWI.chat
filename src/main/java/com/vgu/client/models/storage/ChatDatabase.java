package com.vgu.client.models.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The encrypted local database of one account: a single SQLCipher file opened
 * with a 256-bit key that lives in the account's {@link SecureKeyStore}.
 * One shared connection guarded by a lock; every statement runs through
 * {@link #read} or {@link #write} (a transaction).
 */
public final class ChatDatabase implements AutoCloseable {

    public static final int SCHEMA_VERSION = 3;
    public static final int KEY_BYTES = 32;
    private static final String SCHEMA_RESOURCE = "/com/vgu/client/db/schema.sql";

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    private ChatDatabase(Connection connection) {
        this.connection = connection;
    }

    /** Opens (and on first use creates) the database. Throws if the key is wrong. */
    public static ChatDatabase open(Path file, byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("Database key must be " + KEY_BYTES + " bytes");
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            // file: URI form is the only one that carries the cipher options reliably on Windows paths.
            String url = "jdbc:sqlite:" + file.toAbsolutePath().toUri()
                    + "?cipher=sqlcipher&legacy=4&hexkey=" + HexFormat.of().formatHex(key);
            Connection connection = DriverManager.getConnection(url);
            ChatDatabase db = new ChatDatabase(connection);
            try {
                db.initialize();
            } catch (RuntimeException e) {
                db.close();
                throw e;
            }
            return db;
        } catch (IOException | SQLException e) {
            throw new DatabaseException("Could not open the chat database", e);
        }
    }

    private void initialize() {
        try (Statement st = connection.createStatement()) {
            // Fails here with "file is not a database" when the key is wrong.
            st.execute("SELECT count(*) FROM sqlite_master");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA secure_delete = ON"); // deleted rows are overwritten, not just unlinked

            int version;
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                version = rs.next() ? rs.getInt(1) : 0;
            }
            if (version > SCHEMA_VERSION) {
                throw new DatabaseException("Database was created by a newer version of KIWI.chat", null);
            }
            if (version < SCHEMA_VERSION) {
                connection.setAutoCommit(false);
                try {
                    if (version == 0) {
                        for (String statement : schemaStatements()) {
                            st.execute(statement);
                        }
                    } else {
                        migrate(st, version);
                    }
                    st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                    connection.commit();
                } catch (SQLException | RuntimeException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Wrong key or corrupt chat database", e);
        }
    }

    /** Upgrades an existing database one version at a time. Each step must be safe to run on any older file. */
    private void migrate(Statement st, int from) throws SQLException {
        if (from < 2) {
            addColumnIfMissing(st, "account", "avatar_image", "BLOB");
            addColumnIfMissing(st, "account", "avatar_file_id", "INTEGER");
            addColumnIfMissing(st, "account", "avatar_key", "TEXT");
            addColumnIfMissing(st, "group_sender_keys", "distributed", "INTEGER NOT NULL DEFAULT 0");
        }
        if (from < 3) {
            renamePlaceholders(st);
        }
    }

    private static void renamePlaceholders(Statement st) throws SQLException {
        st.execute("UPDATE contacts SET username = 'user#' || user_id WHERE username = 'user' || user_id");
    }

    private static void addColumnIfMissing(Statement st, String table, String column, String ddl) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
    }

    private static String[] schemaStatements() {
        try (InputStream in = ChatDatabase.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new DatabaseException("schema.sql is missing from the application", null);
            }
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?m)--.*$", ""); // strip comments
            return java.util.Arrays.stream(script.split(";"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        } catch (IOException e) {
            throw new DatabaseException("Could not read schema.sql", e);
        }
    }

    /** Runs read-only queries. */
    public <T> T read(SqlFunction<T> work) {
        lock.lock();
        try {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new DatabaseException("Database query failed: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@code work} in one transaction: commits on success, rolls back on any exception. */
    public <T> T write(SqlFunction<T> work) {
        lock.lock();
        try {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database update failed: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /** Folds the write-ahead log back into the main file and empties it, so deleted data leaves no copy there. */
    public void checkpoint() {
        lock.lock();
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException ignored) {
            // best effort: the data is already deleted logically
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            connection.close();
        } catch (SQLException e) {
            throw new DatabaseException("Could not close the chat database", e);
        } finally {
            lock.unlock();
        }
    }
}
