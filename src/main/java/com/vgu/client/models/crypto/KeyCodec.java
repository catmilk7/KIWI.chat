package com.vgu.client.models.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** RSA key helpers: generation, and conversion to/from the strings the server stores (Base64 of X.509 / PKCS#8). */
public final class KeyCodec {

    public static final int RSA_BITS = 3072;

    private KeyCodec() {
    }

    /** Shared by the encryption and the signing key pair. */
    public static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(RSA_BITS, new SecureRandom());
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("RSA key generation failed", e);
        }
    }

    public static String encodePublic(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublic(String base64) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("Invalid public key", e);
        }
    }

    public static PrivateKey decodePrivate(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Invalid private key", e);
        }
    }
}
