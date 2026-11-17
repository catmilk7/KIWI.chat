package com.vgu.client.storage;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.util.Base64;

/**
 * The two forms a typed password takes. Both start from the same normalisation, so the same
 * visible password always gives the same result, whatever keyboard, input method or operating
 * system produced it.
 *
 * <p><b>Normalisation.</b> Accented letters can be typed as one character (é) or as a letter plus a
 * combining accent (e + ́). They look identical but are different bytes, so a password typed on
 * one system would not match the one registered on another. Everything is converted to NFC first.
 *
 * <p><b>Keystore password.</b> Java's PKCS#12 keystore refuses passwords that are not plain ASCII,
 * which would make a password such as "mật khẩu" impossible to use. A password that is already ASCII
 * is used as is (so existing keystores keep opening); anything else is stretched with PBKDF2 into an
 * ASCII string, salted with the username.
 */
public final class AccountPassword {

    private static final int ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;

    private AccountPassword() {
    }

    /** The password in canonical (NFC) form: this is what the server receives and what its length is measured on. */
    public static String normalized(char[] password) {
        return Normalizer.normalize(new String(password), Normalizer.Form.NFC);
    }

    /** The password that protects this account's keystore file. */
    public static char[] forKeystore(char[] password, String username) {
        String text = normalized(password);
        if (isAscii(text)) {
            return text.toCharArray();
        }
        try {
            byte[] salt = ("kiwi.keystore:" + username).getBytes(StandardCharsets.UTF_8);
            PBEKeySpec spec = new PBEKeySpec(text.toCharArray(), salt, ITERATIONS, KEY_BITS);
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(derived).toCharArray();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        }
    }

    private static boolean isAscii(String text) {
        return text.chars().allMatch(c -> c < 128);
    }
}
