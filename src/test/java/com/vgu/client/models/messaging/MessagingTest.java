package com.vgu.client.models.messaging;

import com.vgu.client.FakeKiwiServer;
import com.vgu.client.controllers.auth.AuthService;
import com.vgu.client.controllers.auth.Session;
import com.vgu.client.enums.DeliveryStatus;
import com.vgu.client.enums.MessageType;
import com.vgu.client.models.crypto.AESCryptoService;
import com.vgu.client.models.crypto.RsaPssSignatureService;
import com.vgu.client.models.network.ApiClient;
import com.vgu.client.models.network.ApiException;
import com.vgu.client.models.network.Dto;
import com.vgu.client.models.network.RealtimeClient;
import com.vgu.client.models.network.ServerEvent;
import com.vgu.client.models.network.ServerApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Two real client stacks (separate keystores and databases) talking through the fake server. */
class MessagingTest {

    @TempDir Path dir;
    FakeKiwiServer server;
    Session alice;
    Session bob;
    long group;

    private Session account(String name) throws Exception {
        AuthService auth = new AuthService(dir, new AESCryptoService(), new RsaPssSignatureService(),
                new ServerApi(new ApiClient(server.url())));
        auth.register(name, "password1".toCharArray());
        return auth.login(name, "password1".toCharArray());
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeKiwiServer();
        alice = account("alice");
        bob = account("bobby");
        group = alice.groups().createGroup("Team");
        bob.groups().join(alice.groups().mintInvite(group).code());
    }

    @AfterEach
    void tearDown() {
        alice.close();
        bob.close();
        server.close();
    }

    private Message say(Session who, String text) throws Exception {
        Message message = who.messages().insertOutgoing(group, MessageType.TEXT, text);
        who.messaging().transmit(message);
        return message;
    }

    @Test
    void aMessageTravelsEncryptedFromOneAccountToTheOther() throws Exception {
        say(alice, "hello team, the secret is 42");

        assertFalse(server.allStoredCiphertext().contains("hello"));
        bob.messaging().syncAll();

        List<Message> seen = bob.messages().list(group);
        assertEquals(1, seen.size());
        assertEquals("hello team, the secret is 42", seen.get(0).content());
        assertEquals("alice", seen.get(0).senderUsername()); // learned from the signed key package
        assertEquals("Team", bob.conversations().list("").get(0).title()); // so was the group name
        assertEquals(1, bob.conversations().list("").get(0).unreadCount());
    }

    @Test
    void theOtherAccountCanAnswerAndTheSenderMarksItsOwnMessageSent() throws Exception {
        Message sent = say(alice, "ping");
        assertEquals(DeliveryStatus.SENT, alice.messages().list(group).get(0).status());
        assertNotNull(alice.messages().list(group).get(0).serverMsgId());

        bob.messaging().syncAll();
        say(bob, "pong");
        alice.messaging().syncAll();

        List<Message> inAlicesDb = alice.messages().list(group);
        assertEquals(2, inAlicesDb.size());
        assertEquals("pong", inAlicesDb.get(1).content());
        assertEquals("bobby", inAlicesDb.get(1).senderUsername());
        assertEquals(sent.clientMsgId(), inAlicesDb.get(0).clientMsgId()); // own message not duplicated by the echo
    }

    @Test
    void theSenderKeyIsHandedOutOncePerEpochNotPerMessage() throws Exception {
        say(alice, "one");
        say(alice, "two");
        say(alice, "three");
        assertEquals(1, server.countMessages("SENDER_KEY"));

        // a new member bumps the epoch: the next message needs a fresh key, sent to everyone
        Session carol = account("carol");
        carol.groups().join(alice.groups().mintInvite(group).code());
        say(alice, "four");
        assertEquals(1 + 2, server.countMessages("SENDER_KEY"));

        carol.messaging().syncAll();
        // the server hides messages from before she joined, and she holds only the new key
        assertEquals(List.of("four"), carol.messages().list(group).stream().map(Message::content).toList());
        carol.close();
    }

    @Test
    void aTamperedMessageIsShownAsUnreadableNotAsGarbage() throws Exception {
        say(alice, "do not touch");
        server.corruptLast("TEXT");
        bob.messaging().syncAll();

        assertEquals(MessagingService.UNREADABLE, bob.messages().list(group).get(0).content());
    }

    @Test
    void aSenderIsNeverNamelessEvenWhenTheMessageCannotBeRead() throws Exception {
        say(alice, "unreadable for bobby");
        server.corruptLast("SENDER_KEY");
        bob.messaging().syncAll();

        Message shown = bob.messages().list(group).get(0);
        assertEquals(MessagingService.UNREADABLE, shown.content());
        assertNotNull(shown.senderUsername()); // shown as "@user#id", never "Unknown"
    }

    @Test
    void aMemberWhoLeavesLosesTheGroupLocallyAndTheOthersSeeTheNewRoster() throws Exception {
        say(alice, "welcome");
        bob.messaging().syncAll();
        assertEquals(1, bob.conversations().list("").size());

        bob.groups().leave(group);

        assertTrue(bob.conversations().list("").isEmpty());
        assertTrue(bob.messages().list(group).isEmpty()); // its messages went with it
        alice.groups().refreshConversations();
        assertTrue(alice.contacts().listMembers(group, alice.userId()).isEmpty()); // bobby is gone from her roster
    }

    @Test
    void anOwnerCannotLeaveWhileOthersRemainButCanDeleteTheGroup() throws Exception {
        ApiException refused = assertThrows(ApiException.class, () -> alice.groups().leave(group));
        assertEquals(403, refused.status());
        assertEquals(1, alice.conversations().list("").size()); // a refused leave changes nothing locally

        alice.groups().delete(group);
        assertTrue(alice.conversations().list("").isEmpty());
        bob.messaging().syncAll(); // reconciles: the group no longer exists for him either
        assertTrue(bob.conversations().list("").isEmpty());
    }

    @Test
    void aKickedMemberLosesTheGroupWhenTheyNextSyncAndTheInviteStopsWorking() throws Exception {
        String code = alice.groups().mintInvite(group).code();
        alice.groups().kick(group, bob.userId());

        assertTrue(alice.contacts().listMembers(group, alice.userId()).isEmpty());
        assertNull(alice.conversations().activeInvite(group)); // the server revoked the code, so did we

        bob.messaging().syncAll(); // he was offline when it happened
        assertTrue(bob.conversations().list("").isEmpty());
        assertEquals(404, assertThrows(ApiException.class, () -> bob.groups().join(code)).status());
    }

    @Test
    void sendingToAGroupWeWereKickedFromRemovesItLocally() throws Exception {
        alice.groups().kick(group, bob.userId());
        assertEquals(1, bob.conversations().list("").size()); // bob has not noticed yet

        Message late = bob.messages().insertOutgoing(group, MessageType.TEXT, "anyone there?");
        assertEquals(403, assertThrows(ApiException.class, () -> bob.messaging().transmit(late)).status());

        assertTrue(bob.conversations().list("").isEmpty()); // the server's refusal is proof enough
        assertTrue(bob.messages().list(group).isEmpty());
    }

    @Test
    void syncResumesFromTheCursorAndNeverDuplicates() throws Exception {
        say(alice, "first");
        bob.messaging().syncAll();
        say(alice, "second");
        bob.messaging().syncAll();
        bob.messaging().syncAll();

        assertEquals(List.of("first", "second"), bob.messages().list(group).stream().map(Message::content).toList());
    }

    @Test
    void anOfflineSendStaysQueuedAndIsRetriedLater() throws Exception {
        Message queued = alice.messages().insertOutgoing(group, MessageType.TEXT, "sent while offline");
        server.close();
        ApiException e = assertThrows(ApiException.class, () -> alice.messaging().transmit(queued));
        assertTrue(e.isNetworkError());
        assertEquals(DeliveryStatus.SENDING, alice.messages().list(group).get(0).status());
        assertEquals(1, alice.messages().pendingOutgoing().size());
    }

    @Test
    void aNewMemberLearnsTheGroupNameAndTheMembersWithoutAnyoneTyping() throws Exception {
        // right after joining, bobby only knows "Group #id" and "user#1"
        assertEquals("Group #" + group, bob.conversations().list("").get(0).title());

        alice.messaging().refreshAndAnnounce(); // alice (owner) notices the new member and hands out her key
        bob.messaging().syncAll();              // bobby receives it, and announces his own in return
        alice.messaging().syncAll();

        assertEquals("Team", bob.conversations().list("").get(0).title());
        assertEquals(List.of("alice"), bob.contacts().listMembers(group, bob.userId()).stream().map(m -> m.username()).toList());
        assertEquals(List.of("bobby"), alice.contacts().listMembers(group, alice.userId()).stream().map(m -> m.username()).toList());
    }

    @Test
    void aMemberNamedLikeAPlaceholderCanStillJoin() throws Exception {
        // alice has server id 1, so a contact we know nothing about yet used to be stored as "user1".
        // An account literally called "user1" then collided with it (usernames are unique) and the join failed.
        Session user1 = account("user1");
        try {
            long joined = user1.groups().join(alice.groups().mintInvite(group).code());
            assertEquals(group, joined);
            assertEquals(1, user1.conversations().list("").size());
            var others = user1.contacts().listMembers(group, user1.userId()).stream().map(m -> m.username()).toList();
            assertEquals(2, others.size()); // alice and bobby, whose names this account does not know yet
            assertTrue(others.contains("user#1"), others.toString()); // alice, under a placeholder that cannot collide
            assertFalse(others.contains("user1"));
        } finally {
            user1.close();
        }
    }

    @Test
    void creatingAndJoiningGroupsSurvivesAConcurrentRefresh() throws Exception {
        // The server pushes CONVERSATION_CHANGED the moment someone joins, and the client also re-syncs
        // every 30 s. Those refreshes run beside the user's own create/join and must not undo them.
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable hammer = () -> {
            while (!stop.get()) {
                try {
                    bob.groups().refreshConversations();
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }
        };
        Thread refresher = new Thread(hammer);
        refresher.start();
        try {
            for (int i = 0; i < 25; i++) {
                long id = alice.groups().createGroup("extra " + i);
                String code = alice.groups().mintInvite(id).code();
                long joined = bob.groups().join(code);
                assertEquals(id, joined);
                assertTrue(bob.conversations().exists(id), "joined group " + id + " vanished");
                assertEquals("MEMBER", bob.conversations().myRole(id));
            }
        } finally {
            stop.set(true);
            refresher.join();
        }
        assertNull(failure.get(), () -> "refresh failed: " + failure.get());
    }

    @Test
    void sessionSendIsAsynchronousAndEventuallySent() throws Exception {
        Message shown = alice.send(group, new TextContent("async hello"));
        assertEquals(DeliveryStatus.SENDING, shown.status()); // visible immediately
        awaitTrue(() -> alice.messages().list(group).get(0).status() == DeliveryStatus.SENT);
    }

    @Test
    void contentKindsAnswerForThemselves() {
        MessageContent text = new TextContent("hello\nthere");
        MessageContent file = new AttachmentContent(new FileAttachment(5, "a2V5", "cv.pdf", 2048));
        List<MessageContent> all = List.of(text, file); // one list, two kinds: polymorphism

        assertEquals(List.of("hello there", "\uD83D\uDCCE Attachment"), all.stream().map(MessageContent::preview).toList());
        assertEquals(List.of(MessageType.TEXT, MessageType.FILE_KEY), all.stream().map(MessageContent::type).toList());
        assertTrue(text.attachment().isEmpty());
        assertEquals("cv.pdf", file.attachment().orElseThrow().name());

        // what is stored comes back as the same kind
        assertEquals(file.toStorage(), MessageContent.parse(MessageType.FILE_KEY, file.toStorage()).toStorage());
        assertInstanceOf(AttachmentContent.class, MessageContent.parse(MessageType.FILE_KEY, file.toStorage()));
        assertInstanceOf(TextContent.class, MessageContent.parse(MessageType.FILE_KEY, "not json")); // safe fallback
    }

    @Test
    void conversationKindsDecideTheirOwnLookAndRules() {
        var last = new ConversationItem.LastMessage(new TextContent("hi"), false, "bobby");
        ConversationItem group = ConversationItem.of(com.vgu.client.enums.ConversationType.GROUP, 1, "Team", 0, java.time.Instant.now(), last);
        ConversationItem direct = ConversationItem.of(com.vgu.client.enums.ConversationType.DIRECT, 2, "@bobby", 0, java.time.Instant.now(), last);

        assertInstanceOf(GroupConversationItem.class, group);
        assertInstanceOf(DirectConversationItem.class, direct);
        assertEquals("@bobby: hi", group.lastPreview());      // the group overrides how a preview names the sender
        assertEquals("hi", direct.lastPreview());              // the direct chat keeps the default
        assertTrue(group.hasSettings());
        assertFalse(direct.hasSettings());
        assertEquals("Delete group", group.leaveLabel(true));
        assertEquals("Leave group", group.leaveLabel(false));
        assertNull(direct.leaveLabel(false));
        assertNotEquals(group.avatarResource(), direct.avatarResource());
        assertEquals("No messages yet", ConversationItem.of(com.vgu.client.enums.ConversationType.GROUP, 3, "x", 0, java.time.Instant.now(), null).lastPreview());
    }

    @Test
    void everyClassCanBeBuiltEmptyAndWithItsParts() {
        // no-argument constructors give a usable blank object ...
        assertEquals("", new TextContent().bubbleText());
        assertEquals(MessageType.FILE_KEY, new AttachmentContent().type());
        assertEquals("No messages yet", new GroupConversationItem().lastPreview());
        assertEquals("", new DirectConversationItem().title());
        assertEquals(0L, new GroupConversationItem().id());
        assertEquals(DeliveryStatus.SENDING, new Message().status());
        assertEquals(0L, new FileAttachment().fileId());
        assertEquals("", new com.vgu.client.models.storage.ContactDao.Member().username());

        // ... and overloaded constructors build the same thing from more or fewer parts
        FileAttachment file = new FileAttachment(7, "a2V5", "cv.pdf", 10);
        assertEquals(file, new AttachmentContent(7, "a2V5", "cv.pdf", 10).attachment().orElseThrow());
        assertEquals("Team", new GroupConversationItem(5, "Team").title());
        Message fresh = new Message(1, "client-1", 9, 2, new TextContent("hi"));
        assertEquals("hi", fresh.content());
        assertNull(fresh.serverMsgId());
        assertEquals("", new TextContent(null).toStorage()); // null is turned into an empty text
        assertEquals(new ApiClient().getClass(), ApiClient.class);
        assertNotNull(new ServerApi().client());
    }

    @Test
    void realtimePushEnvelopesAreTranslated() {
        List<Dto.SyncMessage> got = new ArrayList<>();
        List<String> events = new ArrayList<>();
        ServerEvent.Sink sink = new ServerEvent.Sink() {
            public void messageArrived(Dto.SyncMessage message) { got.add(message); }
            public void conversationsChanged() { events.add("changed"); }
            public void conversationRemoved(long id) { events.add("removed:" + id); }
        };
        RealtimeClient client = new RealtimeClient(new ApiClient("http://127.0.0.1:1"), new RealtimeClient.Listener() {
            public void onConnected() { }
            public void onDisconnected() { }
            public void onEvent(ServerEvent event) { event.deliverTo(sink); } // each kind picks its own reaction
        });
        client.dispatch("{\"type\":\"MESSAGE\",\"conversationId\":12,\"messageId\":88,\"sequence\":88,\"senderId\":1,"
                + "\"epoch\":3,\"clientMsgId\":\"m1\",\"ciphertext\":\"c\",\"iv\":\"i\",\"messageType\":\"TEXT\","
                + "\"createdAt\":\"2026-11-02T03:52:13Z\"}");
        client.dispatch("{\"type\":\"CONVERSATION_CHANGED\",\"conversationId\":12,\"memberIds\":[1,2]}");
        client.dispatch("{\"type\":\"CONVERSATION_REMOVED\",\"conversationId\":13,\"reason\":\"KICKED\"}");
        client.dispatch("{\"type\":\"PONG\"}");
        client.dispatch("not json");

        assertEquals(1, got.size());
        assertEquals("TEXT", got.get(0).type());
        assertEquals(3L, got.get(0).epoch());
        assertNull(got.get(0).recipientId());
        assertEquals(List.of("changed", "removed:13"), events);
        client.close();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("condition not reached within 5 s");
    }
}
