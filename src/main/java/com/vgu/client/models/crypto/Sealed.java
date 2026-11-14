package com.vgu.client.models.crypto;

import java.util.Base64;

/**
 * Output of AES-256-GCM: the 96-bit nonce and the ciphertext (which already
 * ends with the 128-bit authentication tag). Both are sent to the server as
 * Base64 strings.
 */
public record Sealed(byte[] iv, byte[] ciphertext) {

    public String ivBase64() {
        return Base64.getEncoder().encodeToString(iv);
    }

    public String ciphertextBase64() {
        return Base64.getEncoder().encodeToString(ciphertext);
    }

    public static Sealed fromBase64(String iv, String ciphertext) {
        return new Sealed(Base64.getDecoder().decode(iv), Base64.getDecoder().decode(ciphertext));
    }
}
