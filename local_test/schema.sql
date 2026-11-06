PRAGMA foreign_keys = ON;

CREATE TABLE account_identity (
    user_id TEXT INTEGER PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    display_name TEXT,
    encryption_private_key TEXT NOT NULL,
    identity_signing_private_key TEXT NOT NULL,
    access_token TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE contacts (
    user_id TEXT INTEGER PRIMARY KEY,
    username TEXT NOT NULL,
    display_name TEXT,
    avatar_local_path TEXT,
    encryption_public_key TEXT,
    identity_signing_public_key TEXT,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE conversations (
    conversation_id INTEGER PRIMARY KEY,
    type TEXT CHECK(type IN ('DIRECT', 'GROUP')) NOT NULL,
    title TEXT,
    encrypted_meta TEXT, 
    avatar_local_path TEXT,
    direct_user_low INTEGER, 
    direct_user_high INTEGER,
    epoch INTEGER DEFAULT 0, 
    last_msg_id INTEGER DEFAULT 0, 
    unread_count INTEGER DEFAULT 0,
    draft_text TEXT,
    active_invite_code TEXT, 
    invite_expires_at DATETIME, 
    
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE conversation_members (
    conversation_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    role TEXT CHECK(role IN ('OWNER', 'MEMBER')) DEFAULT 'MEMBER',
    joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, user_id),
    FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES contacts(user_id) ON DELETE CASCADE
);

CREATE TABLE messages (
    message_id INTEGER PRIMARY KEY,
    client_msg_id TEXT UNIQUE NOT NULL,
    conversation_id INTEGER NOT NULL,
    sender_id INTEGER NOT NULL,
    recipient_id INTEGER,
    epoch INTEGER,
    msg_type TEXT CHECK(msg_type IN ('TEXT', 'FILE_KEY', 'SENDER_KEY')) NOT NULL,
    ciphertext TEXT,
    iv TEXT,
    plaintext_content TEXT,
    delivery_status TEXT CHECK(delivery_status IN ('SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED')) DEFAULT 'SENT',
    created_at DATETIME NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id, message_id DESC);
CREATE INDEX idx_messages_client_id ON messages(client_msg_id);

CREATE TABLE group_sender_keys (
    conversation_id INTEGER NOT NULL,
    sender_id INTEGER NOT NULL,
    epoch INTEGER NOT NULL,
    symmetric_key TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, sender_id, epoch),
    FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id) ON DELETE CASCADE
);

CREATE TABLE local_file_attachments (
    file_id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_file_id INTEGER,
    message_id INTEGER,
    local_path TEXT NOT NULL,
    file_name TEXT,
    file_size INTEGER,
    mime_type TEXT,
    download_status TEXT CHECK(download_status IN ('PENDING', 'DOWNLOADING', 'COMPLETED', 'FAILED')) DEFAULT 'PENDING',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (message_id) REFERENCES messages(message_id) ON DELETE CASCADE
);