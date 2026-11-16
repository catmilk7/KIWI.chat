package com.vgu.client.models.network;

import com.vgu.client.models.crypto.CryptoService;
import com.vgu.client.models.crypto.Sealed;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Uploads and downloads files the way the server expects: the server only ever sees an
 * encrypted blob. The blob is {@code 12-byte nonce || AES-256-GCM ciphertext}, encrypted with a
 * fresh random key. The file id and the key are the secret you must hand to the recipient (inside
 * an encrypted FILE_KEY message) or keep yourself (your own avatar).
 */
public final class FileService {

    /** What it takes to fetch and read a file again. Treat as a secret. */
    public record FileRef(long fileId, String keyBase64) {
    }

    private static final int NONCE_BYTES = 12;

    private final ServerApi api;
    private final CryptoService crypto;

    public FileService(ServerApi api, CryptoService crypto) {
        this.api = api;
        this.crypto = crypto;
    }

    public FileRef upload(byte[] plaintext) throws ApiException {
        SecretKey key = crypto.generateAesKey();
        Sealed sealed = crypto.encrypt(key, plaintext, null);

        byte[] blob = new byte[sealed.iv().length + sealed.ciphertext().length];
        System.arraycopy(sealed.iv(), 0, blob, 0, sealed.iv().length);
        System.arraycopy(sealed.ciphertext(), 0, blob, sealed.iv().length, sealed.ciphertext().length);

        Dto.DeliveryToken token = api.mintUploadToken();
        Dto.FileUpload uploaded = api.uploadFile(token.token(), blob);
        return new FileRef(uploaded.fileId(), Base64.getEncoder().encodeToString(key.getEncoded()));
    }

    public byte[] download(FileRef ref) throws ApiException {
        byte[] blob = api.downloadFile(ref.fileId());
        if (blob.length <= NONCE_BYTES) {
            throw new ApiException(200, "The downloaded file is too short to be valid");
        }
        byte[] iv = java.util.Arrays.copyOfRange(blob, 0, NONCE_BYTES);
        byte[] ciphertext = java.util.Arrays.copyOfRange(blob, NONCE_BYTES, blob.length);
        SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(ref.keyBase64()), "AES");
        return crypto.decrypt(key, new Sealed(iv, ciphertext), null);
    }
}
