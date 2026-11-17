package com.vgu.client.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.*;

class AccountPasswordTest {

    @TempDir Path dir;

    @Test
    void anAsciiPasswordIsUsedAsItIsSoExistingKeystoresStillOpen() {
        assertArrayEquals("plain-pass-123".toCharArray(), AccountPassword.forKeystore("plain-pass-123".toCharArray(), "alice"));
    }

    @Test
    void anyOtherPasswordBecomesAnAsciiOneThatTheKeystoreAccepts() {
        char[] derived = AccountPassword.forKeystore("mật khẩu 123".toCharArray(), "alice");
        assertTrue(new String(derived).chars().allMatch(c -> c < 128));
        assertEquals(44, derived.length);

        Pkcs12KeyStore store = new Pkcs12KeyStore(dir.resolve("k.p12"), derived);
        store.create();
        store.storeKey("k", new byte[32]);
        assertTrue(store.loadKey("k").isPresent());
    }

    @Test
    void theDerivedPasswordIsStableAndTiedToTheUsername() {
        char[] password = "été 2026".toCharArray();
        assertArrayEquals(AccountPassword.forKeystore(password, "alice"), AccountPassword.forKeystore(password, "alice"));
        assertFalse(java.util.Arrays.equals(AccountPassword.forKeystore(password, "alice"), AccountPassword.forKeystore(password, "bob")));
    }

    @Test
    void composedAndDecomposedLettersGiveTheSameResult() {
        String composed = Normalizer.normalize("été 2026", Normalizer.Form.NFC);
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        assertNotEquals(composed.length(), decomposed.length());

        assertEquals(AccountPassword.normalized(composed.toCharArray()), AccountPassword.normalized(decomposed.toCharArray()));
        assertArrayEquals(AccountPassword.forKeystore(composed.toCharArray(), "u"), AccountPassword.forKeystore(decomposed.toCharArray(), "u"));
    }
}
