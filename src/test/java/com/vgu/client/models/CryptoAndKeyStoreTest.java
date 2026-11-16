package com.vgu.client.models;

import com.vgu.client.models.crypto.*;
import com.vgu.client.models.storage.KeyStoreException;
import com.vgu.client.models.storage.Pkcs12KeyStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CryptoAndKeyStoreTest {

    static final CryptoService crypto = new AESCryptoService();
    static final SignatureService sigs = new RsaPssSignatureService();
    static IdentityKeyPair alice;

    @BeforeAll
    static void keys() {
        alice = IdentityKeyPair.generate(crypto, sigs);
    }

    @Test
    void aesRoundTrip() {
        SecretKey k = crypto.generateAesKey();
        byte[] msg = "hello bob".getBytes(StandardCharsets.UTF_8);
        Sealed s = crypto.encrypt(k, msg, new byte[0]);
        assertEquals(12, s.iv().length);
        assertArrayEquals(msg, crypto.decrypt(k, s, new byte[0]));
    }

    @Test
    void nonceIsFreshEveryTime() {
        SecretKey k = crypto.generateAesKey();
        byte[] m = new byte[]{1};
        assertFalse(Arrays.equals(crypto.encrypt(k, m, null).iv(), crypto.encrypt(k, m, null).iv()));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        SecretKey k = crypto.generateAesKey();
        Sealed s = crypto.encrypt(k, "x".getBytes(), null);
        s.ciphertext()[0] ^= 1;
        assertThrows(CryptoException.class, () -> crypto.decrypt(k, s, null));
    }

    @Test
    void wrongAadIsRejected() {
        SecretKey k = crypto.generateAesKey();
        Sealed s = crypto.encrypt(k, "x".getBytes(), "a".getBytes());
        assertThrows(CryptoException.class, () -> crypto.decrypt(k, s, "b".getBytes()));
    }

    @Test
    void wrapUnwrapKey() {
        SecretKey k = crypto.generateAesKey();
        byte[] wrapped = crypto.wrapKey(alice.encryption().getPublic(), k);
        assertArrayEquals(k.getEncoded(),
                crypto.unwrapKey(alice.encryption().getPrivate(), wrapped).getEncoded());
    }

    @Test
    void wrongPrivateKeyCannotUnwrap() {
        KeyPair other = crypto.generateEncryptionKeyPair();
        byte[] wrapped = crypto.wrapKey(alice.encryption().getPublic(), crypto.generateAesKey());
        assertThrows(CryptoException.class, () -> crypto.unwrapKey(other.getPrivate(), wrapped));
    }

    @Test
    void signatureVerifiesAndRejectsTampering() {
        byte[] data = "envelope".getBytes();
        byte[] sig = sigs.sign(alice.signing().getPrivate(), data);
        assertTrue(sigs.verify(alice.signing().getPublic(), data, sig));
        assertFalse(sigs.verify(alice.signing().getPublic(), "other".getBytes(), sig));
    }

    @Test
    void publicKeyStringRoundTrip() {
        String s = alice.encryptionPublicKeyBase64();
        assertEquals(alice.encryption().getPublic(), KeyCodec.decodePublic(s));
        assertTrue(s.length() < 16_384, "must fit the server's key length limit");
    }

    @Test
    void keystoreStoresIdentityAndRejectsWrongPassword(@TempDir Path dir) {
        Path f = dir.resolve("kiwi.p12");
        Pkcs12KeyStore ks = new Pkcs12KeyStore(f, "correct horse".toCharArray());
        ks.create();
        alice.saveTo(ks);

        IdentityKeyPair loaded = IdentityKeyPair.loadFrom(ks).orElseThrow();
        assertEquals(alice.encryption().getPrivate(), loaded.encryption().getPrivate());
        assertEquals(alice.signing().getPublic(), loaded.signing().getPublic());

        Pkcs12KeyStore bad = new Pkcs12KeyStore(f, "wrong".toCharArray());
        assertThrows(KeyStoreException.class, bad::verifyPassword);
    }

    @Test
    void keystoreDeleteAndMissing(@TempDir Path dir) {
        Pkcs12KeyStore ks = new Pkcs12KeyStore(dir.resolve("a.p12"), "pw".toCharArray());
        ks.create();
        ks.storeKey("db", new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, ks.loadKey("db").orElseThrow());
        ks.deleteKey("db");
        assertTrue(ks.loadKey("db").isEmpty());
        assertTrue(IdentityKeyPair.loadFrom(ks).isEmpty());
    }
}
