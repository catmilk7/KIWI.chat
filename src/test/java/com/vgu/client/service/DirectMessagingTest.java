package com.vgu.client.service;

import com.vgu.client.model.ConversationItem;
import com.vgu.client.model.Message;
import com.vgu.client.FakeKiwiServer;
import com.vgu.client.enums.ConversationType;
import com.vgu.client.enums.DeliveryStatus;
import com.vgu.client.enums.MessageType;
import com.vgu.client.crypto.AESCryptoService;
import com.vgu.client.crypto.RsaPssSignatureService;
import com.vgu.client.network.ApiClient;
import com.vgu.client.network.ApiException;
import com.vgu.client.network.ServerApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Direct (two person) chats: one conversation per pair, a fresh key per message, readable by both sides. */
class DirectMessagingTest {

    @TempDir Path dir;
    FakeKiwiServer server;
    Session alice;
    Session bob;

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
    }

    @AfterEach
    void tearDown() {
        alice.close();
        bob.close();
        server.close();
    }

    private Message say(Session who, long conversation, String text) throws Exception {
        Message message = who.messages().insertOutgoing(conversation, MessageType.TEXT, text);
        who.messaging().transmit(message);
        return message;
    }

    @Test
    void theOtherPersonGetsOurAvatarWithOurNextDirectMessage() throws Exception {
        byte[] avatar = AvatarImage.shrink(AvatarImageTest.image(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB, "png"));
        FileService.FileRef ref = alice.files().upload(avatar);
        alice.account().saveAvatar(avatar, ref.getFileId(), ref.getKeyBase64());

        long chat = alice.messaging().startDirect(bob.userId(), "bobby");
        say(alice, chat, "hello with my picture");
        bob.messaging().syncAll();

        assertArrayEquals(avatar, bob.contacts().avatarImage(alice.userId()));
        assertArrayEquals(avatar, bob.conversations().list("").get(0).getAvatarImage()); // the chat list shows it too
        assertNull(alice.conversations().list("").get(0).getAvatarImage()); // bobby has none, so alice sees the default
    }

    @Test
    void removingAContactDeletesTheChatLocallyAndASyncDoesNotBringItBack() throws Exception {
        long chat = alice.messaging().startDirect(bob.userId(), "bobby");
        say(alice, chat, "hello");

        assertEquals(true, alice.messaging().removeDirect(chat));
        assertFalse(alice.conversations().exists(chat));
        assertEquals(0, alice.messages().list(chat).size());
        assertNull(alice.contacts().keys(bob.userId())); // the contact row is gone too

        alice.messaging().syncAll(); // the server still has the chat, but it must stay removed
        assertFalse(alice.conversations().exists(chat));

        assertEquals(chat, bob.messaging().startDirect(alice.userId(), "alice"));
        say(bob, chat, "hey alice, are you there?"); // when the other person writes, the chat is wanted again
        alice.messaging().syncAll();
        assertEquals(true, alice.conversations().exists(chat));
        assertEquals(1, alice.messages().list(chat).size());
    }

    @Test
    void aDirectMessageTravelsEncryptedBothWaysAndNothingIsReadableOnTheServer() throws Exception {
        long chat = alice.messaging().startDirect(bob.userId(), "bobby");
        say(alice, chat, "hello bobby, the secret is 42");

        assertFalse(server.allStoredCiphertext().contains("hello"));
        assertFalse(server.allStoredCiphertext().contains("alice")); // not even the sender's name
        bob.messaging().syncAll();

        List<Message> seen = bob.messages().list(chat);
        assertEquals(1, seen.size());
        assertEquals("hello bobby, the secret is 42", seen.get(0).getContent());
        assertEquals("alice", seen.get(0).getSenderUsername()); // learned from the sealed message itself
        ConversationItem item = bob.conversations().list("").get(0);
        assertEquals(ConversationType.DIRECT, item.type());
        assertEquals("@alice", item.title());

        say(bob, chat, "got it");
        alice.messaging().syncAll();

        List<Message> mine = alice.messages().list(chat);
        assertEquals(2, mine.size()); // our own message is not duplicated by the sync
        assertEquals("hello bobby, the secret is 42", mine.get(0).getContent());
        assertEquals(DeliveryStatus.SENT, mine.get(0).getStatus());
        assertEquals("got it", mine.get(1).getContent());
        assertEquals("@bobby", alice.conversations().list("").get(0).title());
        assertEquals(0, server.countMessages("SENDER_KEY")); // a direct chat needs no sender key
    }

    @Test
    void openingTheSameDirectChatTwiceGivesTheSameConversation() throws Exception {
        long first = alice.messaging().startDirect(bob.userId(), "bobby");
        long again = alice.messaging().startDirect(bob.userId(), null);
        long fromBob = bob.messaging().startDirect(alice.userId(), "alice");

        assertEquals(first, again);
        assertEquals(first, fromBob);
        assertEquals(1, alice.conversations().list("").size());
    }

    @Test
    void youCannotStartADirectChatWithYourself() {
        assertThrows(ApiException.class, () -> alice.messaging().startDirect(alice.userId(), "alice"));
    }

    @Test
    void aTamperedDirectMessageIsShownAsUnreadableNotAsGarbage() throws Exception {
        long chat = alice.messaging().startDirect(bob.userId(), "bobby");
        say(alice, chat, "do not touch");
        server.corruptLast("TEXT");
        bob.messaging().syncAll();

        assertEquals(MessagingService.UNREADABLE, bob.messages().list(chat).get(0).getContent());
    }
}
