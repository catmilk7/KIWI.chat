package com.vgu.client.models.network;

import com.vgu.client.FakeKiwiServer;
import com.vgu.client.controllers.auth.AuthService;
import com.vgu.client.controllers.auth.Session;
import com.vgu.client.models.crypto.AESCryptoService;
import com.vgu.client.models.crypto.RsaPssSignatureService;
import com.vgu.client.models.messaging.ConversationItem;
import com.vgu.client.models.messaging.GroupService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilesAndGroupsTest {

    static FakeKiwiServer server;
    static Session session;
    @TempDir static Path dir;

    @BeforeAll
    static void login() throws Exception {
        server = new FakeKiwiServer();
        AuthService auth = new AuthService(dir, new AESCryptoService(), new RsaPssSignatureService(),
                new ServerApi(new ApiClient(server.url())));
        auth.register("alice", "password1".toCharArray());
        session = auth.login("alice", "password1".toCharArray());
    }

    @AfterAll
    static void close() {
        session.close();
        server.close();
    }

    @Test
    void uploadedFileIsEncryptedOnTheServerAndRoundTrips() throws Exception {
        byte[] image = "PNG-BYTES-OF-AN-AVATAR".getBytes(StandardCharsets.UTF_8);

        FileService.FileRef ref = session.files().upload(image);

        byte[] stored = server.storedFile(ref.fileId());
        assertNotNull(stored);
        assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("AVATAR"));
        assertArrayEquals(image, session.files().download(ref));

        FileService.FileRef wrongKey = new FileService.FileRef(ref.fileId(),
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        assertThrows(RuntimeException.class, () -> session.files().download(wrongKey));
    }

    @Test
    void deliveryTokenIsSingleUse() throws Exception {
        ServerApi api = new ServerApi(new ApiClient(server.url()));
        api.client().setToken("jwt-alice");
        String token = api.mintUploadToken().token();
        api.uploadFile(token, new byte[]{1, 2, 3});
        ApiException e = assertThrows(ApiException.class, () -> api.uploadFile(token, new byte[]{1, 2, 3}));
        assertEquals(401, e.status());
    }

    @Test
    void avatarIsKeptLocally() throws Exception {
        byte[] image = {9, 8, 7};
        FileService.FileRef ref = session.files().upload(image);
        session.account().saveAvatar(image, ref.fileId(), ref.keyBase64());

        var saved = session.account().avatar();
        assertArrayEquals(image, saved.image());
        assertEquals(ref.fileId(), saved.fileId());
    }

    @Test
    void createdGroupIsStoredLocallyAndTheServerNeverSeesItsName() throws Exception {
        long id = session.groups().createGroup("Secret Project");

        assertFalse(server.storedGroupMeta(id).contains("Secret"));
        List<ConversationItem> list = session.conversations().list("secret");
        assertEquals(1, list.size());
        assertEquals("Secret Project", list.get(0).title());
        assertTrue(list.get(0).isGroup());
        assertEquals("OWNER", session.conversations().myRole(id));
    }

    @Test
    void inviteCodeCanBeMintedAndRedeemed() throws Exception {
        long id = session.groups().createGroup("Invite Group");
        Dto.Invite invite = session.groups().mintInvite(id);
        assertEquals("7Q4M2X9TAB3C", invite.code());
        assertEquals("7Q4M2X9TAB3C", session.conversations().activeInvite(id).code()); // kept in the .db

        long joined = session.groups().join("7q4m-2x9t ab3c"); // same normalisation as the server
        assertEquals(id, joined);

        ApiException unknown = assertThrows(ApiException.class, () -> session.groups().join("ZZZZ-ZZZZ-ZZZZ"));
        assertEquals(404, unknown.status());
        ApiException malformed = assertThrows(ApiException.class, () -> session.groups().join("NOPE"));
        assertEquals(400, malformed.status());
    }

    @Test
    void secondAccountCanJoinWithTheOwnersInviteCode() throws Exception {
        long id = session.groups().createGroup("Shared");
        String code = session.groups().mintInvite(id).code();

        AuthService other = new AuthService(dir, new AESCryptoService(), new RsaPssSignatureService(),
                new ServerApi(new ApiClient(server.url())));
        other.register("bobby", "password1".toCharArray());
        try (Session bob = other.login("bobby", "password1".toCharArray())) {
            assertEquals(id, bob.groups().join(code));

            var list = bob.conversations().list("");
            assertEquals(1, list.size());
            assertEquals("Group #" + id, list.get(0).title()); // name is sealed with the owner's key
            assertEquals("MEMBER", bob.conversations().myRole(id));
            assertEquals(1, bob.contacts().listMembers(id, bob.userId()).size());
        }
    }

    @Test
    void refusedCodeLeavesNothingBehindLocally() throws Exception {
        int before = session.conversations().list("").size();
        assertThrows(ApiException.class, () -> session.groups().join("ZZZZZZZZZZZZ"));
        assertThrows(ApiException.class, () -> session.groups().join("short"));
        assertEquals(before, session.conversations().list("").size());
    }

    @Test
    void handTypedLookAlikeCharactersAreCorrected() {
        assertEquals("7Q4M2X9TAB30", GroupService.normalizeCode(" 7q4m-2x9t-ab3o "));
        assertEquals("1100AAAAAAAA", GroupService.normalizeCode("ilo0aaaaaaaa"));
        assertTrue(GroupService.hasValidShape("7Q4M2X9TAB3C"));
        assertFalse(GroupService.hasValidShape("7Q4M2X9TAB3U")); // U is not in the alphabet
        assertEquals("7Q4M-2X9T-AB3C", GroupService.formatCode("7Q4M2X9TAB3C"));
    }

    @Test
    void localAccountsListsEveryoneWhoRegisteredOnThisDevice() throws Exception {
        AuthService auth = new AuthService(dir, new AESCryptoService(), new RsaPssSignatureService(),
                new ServerApi(new ApiClient(server.url())));
        assertTrue(auth.localAccounts().contains("alice"));
    }

    @Test
    void serverClockOffsetIsLearnedFromTheDateHeader() {
        ApiClient client = new ApiClient("http://127.0.0.1:1");
        long serverTime = java.time.ZonedDateTime.parse("2026-11-14T03:33:07Z").toInstant().toEpochMilli();
        // this machine's clock is 49 s behind the server
        client.observeServerDate("Sat, 14 Nov 2026 03:33:07 GMT", serverTime - 49_000);

        long offset = client.serverNow().toEpochMilli() - System.currentTimeMillis();
        assertTrue(Math.abs(offset - 49_500) < 500, "offset was " + offset);
        client.observeServerDate("garbage", 0); // ignored, keeps the old offset
        assertTrue(Math.abs(client.serverNow().toEpochMilli() - System.currentTimeMillis() - 49_500) < 500);
    }

    @Test
    void unreachableServerIsANetworkError() {
        ServerApi dead = new ServerApi(new ApiClient("http://127.0.0.1:1"));
        ApiException e = assertThrows(ApiException.class, () -> dead.login("x", "y"));
        assertTrue(e.isNetworkError());
        assertFalse(dead.healthy());
    }
}
