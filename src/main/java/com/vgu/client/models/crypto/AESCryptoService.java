package com.vgu.client.models.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;

/** AES-256-GCM for data, RSA-OAEP (SHA-256, MGF1-SHA-256) for key wrapping. */
public final class AESCryptoService implements CryptoService {

    private static final int AES_BITS = 256;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    @Override
    public KeyPair generateEncryptionKeyPair() {
        return KeyCodec.generateRsaKeyPair();
    }

    @Override
    public SecretKey generateAesKey() {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(AES_BITS, random);
            return gen.generateKey();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES key generation failed", e);
        }
    }

    @Override
    public Sealed encrypt(SecretKey key, byte[] plaintext, byte[] aad) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return new Sealed(nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(SecretKey key, Sealed sealed, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed.iv()));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(sealed.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Decryption failed (wrong key or tampered data)", e);
        }
    }

    @Override
    public byte[] wrapKey(PublicKey recipient, SecretKey key) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.ENCRYPT_MODE, recipient, oaep(), random);
            return cipher.doFinal(key.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Key wrapping failed", e);
        }
    }

    @Override
    public SecretKey unwrapKey(PrivateKey own, byte[] wrapped) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, own, oaep());
            return new SecretKeySpec(cipher.doFinal(wrapped), "AES");
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Key unwrapping failed", e);
        }
    }

    // Explicit SHA-256 for both digest and MGF1: the Java default MGF1 uses SHA-1.
    private static OAEPParameterSpec oaep() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT);
    }
}
