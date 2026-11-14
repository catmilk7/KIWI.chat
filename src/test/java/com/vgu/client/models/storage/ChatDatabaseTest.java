package com.vgu.client.models.storage;

import com.vgu.client.enums.ConversationType;
import com.vgu.client.enums.DeliveryStatus;
import com.vgu.client.enums.MessageType;
import com.vgu.client.models.messaging.ConversationItem;
import com.vgu.client.models.messaging.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatDatabaseTest {

    static final long ME = 1;
    static final long BOB = 2;
    static final long CONV = 10;

    @TempDir Path dir;
    Path file;
    byte[] key = new byte[ChatDatabase.KEY_BYTES];
    ChatDatabase db;
    ConversationDao conversations;
    MessageDao messages;
    ContactDao contacts;

    @BeforeEach
    void open() {
        key[0] = 42;
        file = dir.resolve("chat.db");
        db = ChatDatabase.open(file, key);
        new AccountDao(db).create("alice", ME);
        conversations = new ConversationDao(db, ME);
        messages = new MessageDao(db, ME);
        contacts = new ContactDao(db);
        contacts.upsert(BOB, "bob", "enc", "sig");
        conversations.upsert(CONV, ConversationType.DIRECT, null, ME, BOB);
        conversations.addMember(CONV, BOB, "MEMBER");
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void fileIsEncryptedAndNeedsTheRightKey() throws Exception {
        messages.insertOutgoing(CONV, "TOP-SECRET-TEXT");
        db.close();

        assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("TOP-SECRET-TEXT"));
        byte[] wrong = new byte[ChatDatabase.KEY_BYTES];
        assertThrows(DatabaseException.class, () -> ChatDatabase.open(file, wrong));

        db = ChatDatabase.open(file, key);
        assertEquals("TOP-SECRET-TEXT", new MessageDao(db, ME).list(CONV).get(0).content());
    }

    @Test
    void directConversationIsTitledWithThePeersHandle() {
        List<ConversationItem> list = conversations.list("");
        assertEquals(1, list.size());
        assertEquals("@bob", list.get(0).title());
        assertEquals("No messages yet", list.get(0).lastPreview());
        assertEquals(1, conversations.list("BO").size());
        assertTrue(conversations.list("zzz").isEmpty());
        assertTrue(conversations.list("%").isEmpty()); // LIKE wildcards are escaped
    }

    @Test
    void outgoingMessageLifecycle() {
        Message sent = messages.insertOutgoing(CONV, "hello");
        assertEquals(DeliveryStatus.SENDING, sent.status());
        assertEquals("You: hello", conversations.list("").get(0).lastPreview());
        assertEquals(1, messages.pendingOutgoing().size());

        messages.markSent(sent.clientMsgId(), 77);
        Message stored = messages.list(CONV).get(0);
        assertEquals(DeliveryStatus.SENT, stored.status());
        assertEquals(77L, stored.serverMsgId());
        assertTrue(messages.pendingOutgoing().isEmpty());
    }

    @Test
    void incomingMessageIsStoredOnceCountedUnreadAndAdvancesTheCursor() {
        Instant t = Instant.parse("2026-11-02T03:52:13Z");
        assertTrue(messages.saveIncoming(88, "c-1", CONV, BOB, MessageType.TEXT, "hi alice", t));
        assertFalse(messages.saveIncoming(88, "c-1", CONV, BOB, MessageType.TEXT, "hi alice", t)); // duplicate push

        List<Message> list = messages.list(CONV);
        assertEquals(1, list.size());
        assertEquals("bob", list.get(0).senderUsername());
        assertEquals(1, conversations.list("").get(0).unreadCount());
        assertEquals(88, new AccountDao(db).get().syncCursor());

        conversations.markRead(CONV);
        assertEquals(0, conversations.list("").get(0).unreadCount());
    }

    @Test
    void ownMessageEchoedByTheServerIsNotDuplicated() {
        Message sent = messages.insertOutgoing(CONV, "hello");
        assertFalse(messages.saveIncoming(90, sent.clientMsgId(), CONV, ME, MessageType.TEXT, "hello", sent.createdAt()));

        List<Message> list = messages.list(CONV);
        assertEquals(1, list.size());
        assertEquals(90L, list.get(0).serverMsgId());
        assertEquals(0, conversations.list("").get(0).unreadCount());
    }

    @Test
    void attachmentMessageKeepsItsFileReferenceAndShowsAsAttachmentInTheList() {
        var file = new com.vgu.client.models.messaging.FileAttachment(42, "a2V5", "cv.pdf", 2048);
        Message sent = messages.insertOutgoingFile(CONV, file);

        assertEquals(MessageType.FILE_KEY, sent.type());
        var stored = com.vgu.client.models.messaging.FileAttachment.fromJson(messages.list(CONV).get(0).content());
        assertEquals(file, stored);
        assertTrue(conversations.list("").get(0).lastPreview().contains("Attachment"));
        assertNull(com.vgu.client.models.messaging.FileAttachment.fromJson("just text"));
    }

    @Test
    void inviteCodeIsStoredOnTheConversationRowAndCleanedWhenItExpires() {
        conversations.upsert(11, ConversationType.GROUP, "team", null, null);
        assertNull(conversations.activeInvite(11));

        conversations.saveInvite(11, "7Q4M2X9TAB3C", Instant.now().plusSeconds(3600));
        var invite = conversations.activeInvite(11);
        assertEquals("7Q4M2X9TAB3C", invite.code());
        assertTrue(invite.expiresAt().isAfter(Instant.now()));

        // survives reopening the database
        db.close();
        db = ChatDatabase.open(file, key);
        conversations = new ConversationDao(db, ME);
        assertEquals("7Q4M2X9TAB3C", conversations.activeInvite(11).code());

        conversations.saveInvite(11, "OLDCODE", Instant.now().minusSeconds(5));
        assertNull(conversations.activeInvite(11));
        // the row itself was updated, not just hidden
        assertTrue(db.<Boolean>read(c -> {
            try (var rs = c.createStatement().executeQuery(
                    "SELECT active_invite_code IS NULL AND invite_expires_at IS NULL FROM conversations WHERE conversation_id = 11")) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }));
    }

    @Test
    void bindingTheRealUserIdMovesEverythingWrittenWhileLocal() {
        // a second account database that was used before the server assigned an id (user id 0)
        db.close();
        db = ChatDatabase.open(dir.resolve("legacy.db"), key);
        new AccountDao(db).create("legacy", AccountDao.LOCAL_USER_ID);
        var legacyConversations = new ConversationDao(db, 0);
        var legacyContacts = new ContactDao(db);
        var legacyMessages = new MessageDao(db, 0);
        legacyContacts.upsert(0, "legacy", null, null);
        legacyConversations.upsert(5, ConversationType.GROUP, "old group", null, null);
        legacyConversations.addMember(5, 0, "OWNER");
        legacyMessages.insertOutgoing(5, "written offline");

        new AccountDao(db).bindUserId(42);

        var asReal = new ConversationDao(db, 42);
        assertEquals("OWNER", asReal.myRole(5));
        assertEquals(42L, new MessageDao(db, 42).list(5).get(0).senderId());
        assertEquals("legacy", new MessageDao(db, 42).list(5).get(0).senderUsername());
        assertTrue(new ContactDao(db).listMembers(5, 42).isEmpty()); // no ghost "local" member left behind
    }

    @Test
    void forgettingAConversationLeavesNothingBehind() throws Exception {
        conversations.upsert(30, ConversationType.GROUP, "doomed", null, null);
        contacts.upsert(ME, "alice", null, null);
        contacts.upsert(77, "carol", "enc", "sig");
        conversations.addMember(30, ME, "MEMBER");
        conversations.addMember(30, 77, "OWNER");
        new GroupKeyDao(db).save(30, 77, 0, "a2V5", true);
        messages.saveIncoming(500, "c-30", 30, 77, MessageType.TEXT, "secret words", Instant.now());
        messages.insertOutgoingFile(30, new com.vgu.client.models.messaging.FileAttachment(9, "a2V5", "x.pdf", 10));
        conversations.saveInvite(30, "7Q4M2X9TAB3C", Instant.now().plusSeconds(60));

        conversations.delete(30);

        for (String table : new String[]{"conversations", "conversation_members", "messages", "group_sender_keys",
                "local_file_attachments"}) {
            assertEquals(0, count("SELECT count(*) FROM " + table + " WHERE "
                    + (table.equals("local_file_attachments") ? "server_file_id = 9" : "conversation_id = 30")), table);
        }
        assertEquals(0, count("SELECT count(*) FROM contacts WHERE user_id = 77")); // only known through that group
        assertEquals(1, count("SELECT count(*) FROM contacts WHERE user_id = " + BOB)); // still a direct-chat peer
        assertEquals(1, conversations.list("").size()); // the direct chat is untouched
    }

    @Test
    void reconcilingWithTheServerRemovesEverythingForGroupsWeNoLongerBelongTo() throws Exception {
        conversations.upsert(31, ConversationType.GROUP, "gone", null, null);
        contacts.upsert(78, "dave", null, null);
        conversations.addMember(31, 78, "OWNER");
        messages.saveIncoming(501, "c-31", 31, 78, MessageType.TEXT, "hi", Instant.now());

        assertEquals(1, conversations.deleteAllExcept(java.util.Set.of(CONV)));

        assertEquals(0, count("SELECT count(*) FROM messages WHERE conversation_id = 31"));
        assertEquals(0, count("SELECT count(*) FROM contacts WHERE user_id = 78"));
        assertEquals(1, conversations.list("").size());
    }

    private int count(String sql) {
        return db.<Integer>read(c -> {
            try (var rs = c.createStatement().executeQuery(sql)) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    @Test
    void anOldDatabaseHasItsPlaceholderNamesMadeSafe() throws Exception {
        Path old = dir.resolve("old.db");
        try (ChatDatabase legacy = ChatDatabase.open(old, key)) {
            legacy.write(c -> {
                c.createStatement().execute("INSERT INTO contacts (user_id, username, updated_at) VALUES (7, 'user7', 'x')");
                c.createStatement().execute("INSERT INTO contacts (user_id, username, updated_at) VALUES (8, 'real_name', 'x')");
                c.createStatement().execute("PRAGMA user_version = 2"); // as written by the previous release
                return null;
            });
        }
        try (ChatDatabase upgraded = ChatDatabase.open(old, key)) {
            var names = upgraded.<java.util.List<String>>read(c -> {
                var out = new java.util.ArrayList<String>();
                try (var rs = c.createStatement().executeQuery("SELECT username FROM contacts ORDER BY user_id")) {
                    while (rs.next()) out.add(rs.getString(1));
                }
                return out;
            });
            assertEquals(java.util.List.of("user#7", "real_name"), names);
        }
    }

    @Test
    void messagesFollowTheServersOrderNotAnyClock() {
        // The server's clock runs a minute ahead of ours: its message is stamped "in the future".
        Instant serverNow = Instant.now().plusSeconds(60);
        messages.saveIncoming(500, "theirs-1", CONV, BOB, MessageType.TEXT, "from bob", serverNow);

        Message mine = messages.insertOutgoing(CONV, "written after, stamped by our slower clock");
        assertEquals(List.of("from bob", "written after, stamped by our slower clock"),
                messages.list(CONV).stream().map(Message::content).toList()); // unsent goes last, not first

        messages.markSent(mine.clientMsgId(), 501, serverNow.plusSeconds(1));
        messages.saveIncoming(502, "theirs-2", CONV, BOB, MessageType.TEXT, "bob again", serverNow.plusSeconds(2));
        assertEquals(List.of("from bob", "written after, stamped by our slower clock", "bob again"),
                messages.list(CONV).stream().map(Message::content).toList());
        assertEquals(serverNow.plusSeconds(1), messages.list(CONV).get(1).createdAt()); // shown with the server's time

        // ... and the conversation list previews the newest message in that same order
        assertEquals("bob again", conversations.list("").get(0).lastPreview());
    }

    @Test
    void failedWriteRollsBackEverything() {
        assertThrows(DatabaseException.class, () -> db.write(c -> {
            try (var ps = c.prepareStatement("UPDATE account SET sync_cursor = 5")) {
                ps.executeUpdate();
            }
            throw new java.sql.SQLException("boom");
        }));
        assertEquals(0, new AccountDao(db).get().syncCursor());
    }

    @Test
    void membersExcludeTheAccountOwner() {
        conversations.upsert(11, ConversationType.GROUP, "team", null, null);
        contacts.upsert(ME, "alice", null, null);
        conversations.addMember(11, ME, "OWNER");
        conversations.addMember(11, BOB, "MEMBER");
        var members = contacts.listMembers(11, ME);
        assertEquals(1, members.size());
        assertEquals("bob", members.get(0).username());
    }
}
