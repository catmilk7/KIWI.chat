package com.vgu.client.models.crypto;

import java.util.Base64;

/**
 * Output of AES-256-GCM: the 96-bit nonce and the ciphertext (which already
 * ends with the 128-bit authentication tag). Both are sent to the server as
 * Base64 strings.
 */
public final class Sealed {
    private final byte[] iv;
    private final byte[] ciphertext;

    public Sealed(byte[] iv, byte[] ciphertext) {
        this.iv = iv;
        this.ciphertext = ciphertext;
    }

    public byte[] getIv() {
        return iv;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }


    public String ivBase64() {
        return Base64.getEncoder().encodeToString(iv);
    }

    public String ciphertextBase64() {
        return Base64.getEncoder().encodeToString(ciphertext);
    }
}
