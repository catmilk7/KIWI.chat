package com.vgu.client.models.crypto;

import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Confidentiality operations. Signatures live in {@link SignatureService}.
 * The rest of the client depends on this interface only, so the algorithms can
 * be replaced without touching chat logic.
 */
public interface CryptoService {

    /** New RSA key pair used for key wrapping (RSA-OAEP). */
    KeyPair generateEncryptionKeyPair();

    /** New random AES-256 key. */
    SecretKey generateAesKey();

    /** AES-256-GCM with a fresh random 96-bit nonce. {@code aad} may be empty. */
    Sealed encrypt(SecretKey key, byte[] plaintext, byte[] aad);

    /** Throws {@link CryptoException} if the tag does not verify. */
    byte[] decrypt(SecretKey key, Sealed sealed, byte[] aad);

    /** Encrypts an AES key for a recipient with RSA-OAEP (SHA-256). */
    byte[] wrapKey(PublicKey recipient, SecretKey key);

    /** Reverses {@link #wrapKey}. */
    SecretKey unwrapKey(PrivateKey own, byte[] wrapped);
}
