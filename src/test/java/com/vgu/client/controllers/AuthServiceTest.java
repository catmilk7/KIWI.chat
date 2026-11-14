package com.vgu.client.controllers;

import com.vgu.client.FakeKiwiServer;
import com.vgu.client.controllers.auth.AuthException;
import com.vgu.client.controllers.auth.AuthService;
import com.vgu.client.controllers.auth.Session;
import com.vgu.client.models.crypto.AESCryptoService;
import com.vgu.client.models.crypto.IdentityKeyPair;
import com.vgu.client.models.crypto.RsaPssSignatureService;
import com.vgu.client.models.network.ApiClient;
import com.vgu.client.models.network.ServerApi;
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
    void wrongPasswordAndUnknownUserGiveTheSameError() throws Exception {
        service().register("carol", "right-password".toCharArray());
        AuthException wrong = assertThrows(AuthException.class, () -> service().login("carol", "wrong-password".toCharArray()));
        AuthException unknown = assertThrows(AuthException.class, () -> service().login("nobody", "whatever1".toCharArray()));
        assertEquals(wrong.getMessage(), unknown.getMessage());
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
