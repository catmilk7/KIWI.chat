package com.vgu.client.service;

import com.vgu.client.FakeKiwiServer;
import com.vgu.client.crypto.AESCryptoService;
import com.vgu.client.crypto.IdentityKeyPair;
import com.vgu.client.crypto.RsaPssSignatureService;
import com.vgu.client.network.ApiClient;
import com.vgu.client.network.ServerApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    @TempDir
    Path dir;
    FakeKiwiServer server;

    @BeforeEach
    void start() throws IOException {
        server = new FakeKiwiServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private AuthService service() {
        return new AuthService(dir, new AESCryptoService(), new RsaPssSignatureService(),
                new ServerApi(new ApiClient(server.url())));
    }

    @Test
    void registerSendsPublicKeysAndLoginReturnsTheSameIdentity() throws Exception {
        IdentityKeyPair created = service().register("alice", "password1".toCharArray());

        String sent = server.registrationBodies().get(0);
        assertTrue(sent.contains(created.encryptionPublicKeyBase64()));
        assertTrue(sent.contains(created.signingPublicKeyBase64()));
        assertFalse(sent.toLowerCase().contains("displayname"));

        try (Session session = service().login("alice", "password1".toCharArray())) {
            assertEquals("@alice", session.handle());
            assertEquals(1L, session.userId());           // the id the server assigned
            assertTrue(session.online());
            assertEquals(created.signingPublicKeyBase64(), session.identity().signingPublicKeyBase64());
            assertTrue(session.conversations().list("").isEmpty());
        }
    }

    @Test
    void inputsTheServerWouldRejectNeverLeaveTheClient() {
        AuthException shortName = assertThrows(AuthException.class,
                () -> service().register("ab", "password1".toCharArray()));
        AuthException shortPassword = assertThrows(AuthException.class,
                () -> service().register("alice", "short".toCharArray()));
        assertTrue(shortName.getMessage().contains("3-100"));
        assertTrue(shortPassword.getMessage().contains("8-72"));
        assertEquals(0, server.calls());
    }

    @Test
    void takenUsernameIsReportedAndLeavesNoLocalData() throws Exception {
        service().register("bob", "password1".toCharArray());
        Path otherDevice = dir.resolve("other");
        AuthService second = new AuthService(otherDevice, new AESCryptoService(), new RsaPssSignatureService(),
                new ServerApi(new ApiClient(server.url())));

        AuthException e = assertThrows(AuthException.class, () -> second.register("bob", "password2".toCharArray()));
        assertEquals("That username is already taken.", e.getMessage());
        assertFalse(java.nio.file.Files.exists(otherDevice.resolve("bob")));
    }

    @Test
    void aWrongPasswordIsReportedAsSuch() throws Exception {
        service().register("carol", "right-password".toCharArray());
        AuthException wrong = assertThrows(AuthException.class, () -> service().login("carol", "wrong-password".toCharArray()));
        assertEquals("Wrong username or password.", wrong.getMessage());
    }

    @Test
    void anAccountRegisteredOnAnotherDeviceSaysSoInsteadOfBlamingThePassword() throws Exception {
        service().register("dora", "password1".toCharArray());
        Path otherDevice = dir.resolve("other-device");
        AuthService there = new AuthService(otherDevice, new AESCryptoService(), new RsaPssSignatureService(),
                new ServerApi(new ApiClient(server.url())));

        AuthException e = assertThrows(AuthException.class, () -> there.login("dora", "password1".toCharArray()));
        assertTrue(e.getMessage().startsWith("This device has no keys for @dora"), e.getMessage());
        assertTrue(e.getMessage().contains("dora"));
    }

    @Test
    void copyingAnAccountFolderToAnotherDeviceMakesItUsableThere() throws Exception {
        service().register("emil", "password1".toCharArray());
        Path otherDevice = dir.resolve("copy-target");
        java.nio.file.Files.createDirectories(otherDevice.resolve("emil"));
        for (String file : new String[]{"keystore.p12", "chat.db"}) {
            java.nio.file.Files.copy(dir.resolve("emil").resolve(file), otherDevice.resolve("emil").resolve(file));
        }
        AuthService there = new AuthService(otherDevice, new AESCryptoService(), new RsaPssSignatureService(),
                new ServerApi(new ApiClient(server.url())));

        try (Session session = there.login("emil", "password1".toCharArray())) {
            assertEquals("@emil", session.handle());
            assertTrue(session.online());
        }
    }

    @Test
    void passwordsWithAccentedLettersWork() throws Exception {
        String password = "m\u1eadt kh\u1ea9u 123"; // "mật khẩu 123", which Java's PKCS#12 keystore cannot take as it is
        service().register("viet", password.toCharArray());

        try (Session session = service().login("viet", password.toCharArray())) {
            assertEquals("@viet", session.handle());
        }
    }

    @Test
    void theSamePasswordTypedOnAnotherSystemStillMatches() throws Exception {
        String composed = java.text.Normalizer.normalize("m\u1eadt kh\u1ea9u 123", java.text.Normalizer.Form.NFC);
        String decomposed = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFD);
        assertNotEquals(composed, decomposed); // look identical, different characters

        service().register("sam", composed.toCharArray());
        try (Session session = service().login("sam", decomposed.toCharArray())) {
            assertEquals("@sam", session.handle());
        }
    }

    @Test
    void aFailedLocalSetupLeavesNothingOnTheServer() throws Exception {
        // the folder name is taken by a plain file, so no keystore can be created there
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("blocked"), "in the way");

        assertThrows(AuthException.class, () -> service().register("blocked", "password1".toCharArray()));
        assertEquals(0, server.registrationBodies().size(), "the server must not be told about an account that has no keys");
    }

    @Test
    void loginWorksOfflineWhenTheServerIsUnreachable() throws Exception {
        service().register("dave", "password1".toCharArray());
        server.close();

        try (Session session = service().login("dave", "password1".toCharArray())) {
            assertFalse(session.online());
            assertEquals("@dave", session.handle());
        }
    }

    @Test
    void registrationFailsCleanlyWhenTheServerIsUnreachable() {
        server.close();
        AuthException e = assertThrows(AuthException.class, () -> service().register("erin", "password1".toCharArray()));
        assertTrue(e.getMessage().startsWith("Cannot reach the server"));
        assertFalse(java.nio.file.Files.exists(dir.resolve("erin")));
    }

    @Test
    void illegalUsernameCannotEscapeTheDataFolder() {
        assertThrows(AuthException.class, () -> service().login("../evil", "password1".toCharArray()));
    }
}
