package com.vgu.client.models.messaging;

import com.google.gson.Gson;
import com.vgu.client.enums.MessageType;
import com.vgu.client.models.crypto.CryptoException;
import com.vgu.client.models.crypto.CryptoService;
import com.vgu.client.models.crypto.IdentityKeyPair;
import com.vgu.client.models.crypto.Sealed;
import com.vgu.client.models.crypto.SignatureService;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Builds and opens the opaque {@code ciphertext}/{@code iv} strings the server stores. Pure
 * crypto, no network or database. The server never sees plaintext, keys or signatures.
 *
 * <p>Group messages (TEXT, FILE_KEY): AES-256-GCM under the sender's key for the current epoch,
 * signed (RSA-PSS) by the sender's identity key.<br>
 * Sender-key packages (SENDER_KEY): the package is sealed with a fresh AES key, that key is
 * wrapped for the recipient (RSA-OAEP) and the whole thing is signed.<br>
 * Everything that identifies the message (type, conversation, sender, recipient, epoch,
 * clientMsgId) is bound in as AAD and signed, so a message cannot be replayed elsewhere.
 */
public final class MessageCrypto {

    /** The two strings that travel to the server: {@code ciphertext} and {@code iv} (Base64). */
    public record Envelope(String ciphertext, String iv) {
    }

    /** What a sender key package carries: the key, who the sender is, and (if known) the group name. */
    public record SenderKeyPackage(String key, String username, String groupName) {
    }

    private final CryptoService crypto;
    private final SignatureService signatures;
    private final IdentityKeyPair identity;
    private final Gson gson = new Gson();

    public MessageCrypto(CryptoService crypto, SignatureService signatures, IdentityKeyPair identity) {
        this.crypto = crypto;
        this.signatures = signatures;
        this.identity = identity;
    }

    public SecretKey newSenderKey() {
        return crypto.generateAesKey();
    }

    // ---- group messages ----

    public Envelope sealGroup(SecretKey senderKey, MessageType type, long conversationId, long senderId, long epoch,
                              String clientMsgId, String plaintext) {
        byte[] aad = aad(type, conversationId, senderId, null, epoch, clientMsgId);
        Sealed sealed = crypto.encrypt(senderKey, plaintext.getBytes(StandardCharsets.UTF_8), aad);
        byte[] signature = signatures.sign(identity.signing().getPrivate(),
                signedBytes(aad, sealed.iv(), sealed.ciphertext()));
        return new Envelope(b64(sealed.ciphertext()) + "." + b64(signature), b64(sealed.iv()));
    }

    /** @throws CryptoException if the signature or the authentication tag does not verify */
    public String openGroup(SecretKey senderKey, PublicKey senderSigningKey, MessageType type, long conversationId,
                            long senderId, long epoch, String clientMsgId, Envelope envelope) {
        String[] parts = split(envelope.ciphertext(), 2);
        byte[] ciphertext = unb64(parts[0]);
        byte[] iv = unb64(envelope.iv());
        byte[] aad = aad(type, conversationId, senderId, null, epoch, clientMsgId);
        if (!signatures.verify(senderSigningKey, signedBytes(aad, iv, ciphertext), unb64(parts[1]))) {
            throw new CryptoException("Bad message signature");
        }
        return new String(crypto.decrypt(senderKey, new Sealed(iv, ciphertext), aad), StandardCharsets.UTF_8);
    }

    // ---- sender key packages ----

    public Envelope sealPackage(PublicKey recipientEncryptionKey, long conversationId, long senderId, long recipientId,
                                long epoch, String clientMsgId, SenderKeyPackage pkg) {
        byte[] aad = aad(MessageType.SENDER_KEY, conversationId, senderId, recipientId, epoch, clientMsgId);
        SecretKey packageKey = crypto.generateAesKey();
        byte[] wrapped = crypto.wrapKey(recipientEncryptionKey, packageKey);
        Sealed sealed = crypto.encrypt(packageKey, gson.toJson(pkg).getBytes(StandardCharsets.UTF_8), aad);
        byte[] signature = signatures.sign(identity.signing().getPrivate(),
                signedBytes(aad, sealed.iv(), wrapped, sealed.ciphertext()));
        return new Envelope(b64(wrapped) + "." + b64(sealed.ciphertext()) + "." + b64(signature), b64(sealed.iv()));
    }

    /** @throws CryptoException if the signature, the key unwrapping or the authentication tag fails */
    public SenderKeyPackage openPackage(PrivateKey ownEncryptionKey, PublicKey senderSigningKey, long conversationId,
                                        long senderId, long recipientId, long epoch, String clientMsgId,
                                        Envelope envelope) {
        String[] parts = split(envelope.ciphertext(), 3);
        byte[] wrapped = unb64(parts[0]);
        byte[] ciphertext = unb64(parts[1]);
        byte[] iv = unb64(envelope.iv());
        byte[] aad = aad(MessageType.SENDER_KEY, conversationId, senderId, recipientId, epoch, clientMsgId);
        if (!signatures.verify(senderSigningKey, signedBytes(aad, iv, wrapped, ciphertext), unb64(parts[2]))) {
            throw new CryptoException("Bad sender key signature");
        }
        SecretKey packageKey = crypto.unwrapKey(ownEncryptionKey, wrapped);
        byte[] json = crypto.decrypt(packageKey, new Sealed(iv, ciphertext), aad);
        try {
            SenderKeyPackage pkg = gson.fromJson(new String(json, StandardCharsets.UTF_8), SenderKeyPackage.class);
            if (pkg == null || pkg.key() == null) {
                throw new CryptoException("Empty sender key package");
            }
            return pkg;
        } catch (com.google.gson.JsonParseException e) {
            throw new CryptoException("Malformed sender key package", e);
        }
    }

    public static SecretKey keyFromBase64(String base64) {
        return new SecretKeySpec(Base64.getDecoder().decode(base64), "AES");
    }

    public static String keyToBase64(SecretKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    // ---- helpers ----

    private static byte[] aad(MessageType type, long conversationId, long senderId, Long recipientId, long epoch,
                              String clientMsgId) {
        return ("KIWI|" + type.name() + "|" + conversationId + "|" + senderId + "|"
                + (recipientId == null ? "" : recipientId) + "|" + epoch + "|" + clientMsgId)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Length-prefixed concatenation, so different splits of the same bytes never produce the same input. */
    private static byte[] signedBytes(byte[]... parts) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            for (byte[] part : parts) {
                out.writeInt(part.length);
                out.write(part);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new CryptoException("Could not assemble the signed data", e);
        }
    }

    private static String[] split(String ciphertext, int expected) {
        String[] parts = ciphertext == null ? new String[0] : ciphertext.split("\\.", -1);
        if (parts.length != expected) {
            throw new CryptoException("Malformed envelope");
        }
        return parts;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] unb64(String s) {
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new CryptoException("Malformed envelope", e);
        }
    }
}
