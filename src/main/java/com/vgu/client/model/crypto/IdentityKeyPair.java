package com.vgu.client.model.crypto;

import com.vgu.client.model.storage.SecureKeyStore;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Optional;

/**
 * A user's two long-term RSA key pairs: one for encryption (OAEP), one for
 * identity signing (PSS). Private halves never leave the {@link SecureKeyStore}.
 */
public record IdentityKeyPair(KeyPair encryption, KeyPair signing) {

    private static final String ENC_PRIV = "identity.enc.private";
    private static final String ENC_PUB = "identity.enc.public";
    private static final String SIG_PRIV = "identity.sig.private";
    private static final String SIG_PUB = "identity.sig.public";

    public static IdentityKeyPair generate(CryptoService crypto, SignatureService signatures) {
        return new IdentityKeyPair(crypto.generateEncryptionKeyPair(), signatures.generateSigningKeyPair());
    }

    public void saveTo(SecureKeyStore store) {
        store.storeKey(ENC_PRIV, encryption.getPrivate().getEncoded());
        store.storeKey(ENC_PUB, encryption.getPublic().getEncoded());
        store.storeKey(SIG_PRIV, signing.getPrivate().getEncoded());
        store.storeKey(SIG_PUB, signing.getPublic().getEncoded());
    }

    public static Optional<IdentityKeyPair> loadFrom(SecureKeyStore store) {
        Optional<byte[]> ep = store.loadKey(ENC_PRIV), eb = store.loadKey(ENC_PUB);
        Optional<byte[]> sp = store.loadKey(SIG_PRIV), sb = store.loadKey(SIG_PUB);
        if (ep.isEmpty() || eb.isEmpty() || sp.isEmpty() || sb.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new IdentityKeyPair(
                new KeyPair(publicFrom(eb.get()), KeyCodec.decodePrivate(ep.get())),
                new KeyPair(publicFrom(sb.get()), KeyCodec.decodePrivate(sp.get()))));
    }

    private static PublicKey publicFrom(byte[] x509) {
        return KeyCodec.decodePublic(Base64.getEncoder().encodeToString(x509));
    }

    public String encryptionPublicKeyBase64() {
        return KeyCodec.encodePublic(encryption.getPublic());
    }

    public String signingPublicKeyBase64() {
        return KeyCodec.encodePublic(signing.getPublic());
    }
}
