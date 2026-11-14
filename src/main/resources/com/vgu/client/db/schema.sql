-- Local chat database, one encrypted file per account (SQLCipher).
-- Times are ISO-8601 UTC strings. Ids from the server are positive; user_id 0 means "this
-- account before the server has assigned one".

CREATE TABLE account (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    user_id     INTEGER NOT NULL,
    username    TEXT NOT NULL,
    sync_cursor INTEGER NOT NULL DEFAULT 0,       -- last server message id received (GET /api/sync?since=)
    avatar_image   BLOB,                          -- own avatar, plaintext (the whole file is encrypted)
    avatar_file_id INTEGER,                       -- the encrypted copy on the server
    avatar_key     TEXT                           -- its AES key, Base64
);

CREATE TABLE contacts (
    user_id                     INTEGER PRIMARY KEY,
    username                    TEXT NOT NULL UNIQUE,
    avatar_local_path           TEXT,
    encryption_public_key       TEXT,
    identity_signing_public_key TEXT,
    updated_at                  TEXT NOT NULL
);

CREATE TABLE conversations (
    conversation_id     INTEGER PRIMARY KEY,
    type                TEXT NOT NULL CHECK (type IN ('DIRECT', 'GROUP')),
    title               TEXT,
    encrypted_meta      TEXT,
    avatar_local_path   TEXT,
    direct_user_low     INTEGER,
    direct_user_high    INTEGER,
    epoch               INTEGER NOT NULL DEFAULT 0,
    last_msg_id         INTEGER NOT NULL DEFAULT 0,
    unread_count        INTEGER NOT NULL DEFAULT 0,
    draft_text          TEXT,
    active_invite_code  TEXT,
    invite_expires_at   TEXT,
    updated_at          TEXT NOT NULL,
    UNIQUE (direct_user_low, direct_user_high)
);

CREATE TABLE conversation_members (
    conversation_id INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    role            TEXT NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER', 'MEMBER')),
    joined_at       TEXT NOT NULL,
    PRIMARY KEY (conversation_id, user_id),
    FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES contacts(user_id) ON DELETE CASCADE
);

-- SENDER_KEY packages are not stored here: they are consumed into group_sender_keys.
CREATE TABLE messages (
    local_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    server_msg_id     INTEGER UNIQUE,                -- NULL until the server has acknowledged the message
    client_msg_id     TEXT NOT NULL,
    conversation_id   INTEGER NOT NULL,
    sender_id         INTEGER NOT NULL,
    epoch             INTEGER,
    msg_type          TEXT NOT NULL CHECK (msg_type IN ('TEXT', 'FILE_KEY')),
    plaintext_content TEXT,
    ciphertext        TEXT,                          -- kept only while the message is waiting to be sent
    iv                TEXT,
    delivery_status   TEXT NOT NULL DEFAULT 'SENT'
                      CHECK (delivery_status IN ('SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED')),
    created_at        TEXT NOT NULL,
    UNIQUE (conversation_id, sender_id, client_msg_id),
    FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id, local_id DESC);
CREATE INDEX idx_messages_outbox ON messages(delivery_status) WHERE delivery_status IN ('SENDING', 'FAILED');

CREATE TABLE group_sender_keys (
    conversation_id INTEGER NOT NULL,
    sender_id       INTEGER NOT NULL,
    epoch           INTEGER NOT NULL,
    symmetric_key   TEXT NOT NULL,
    distributed     INTEGER NOT NULL DEFAULT 0,      -- own keys: 1 once every member has received it
    created_at      TEXT NOT NULL,
    PRIMARY KEY (conversation_id, sender_id, epoch),
    FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id) ON DELETE CASCADE
);

CREATE TABLE local_file_attachments (
    file_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    server_file_id  INTEGER,
    message_id      INTEGER,                         -- messages.local_id
    local_path      TEXT NOT NULL,
    file_name       TEXT,
    file_size       INTEGER,
    mime_type       TEXT,
    download_status TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (download_status IN ('PENDING', 'DOWNLOADING', 'COMPLETED', 'FAILED')),
    created_at      TEXT NOT NULL,
    FOREIGN KEY (message_id) REFERENCES messages(local_id) ON DELETE CASCADE
);
