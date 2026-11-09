package com.vgu.client.model.storage;

import java.util.Optional;

/** Protected storage for secret bytes (private keys, the database key). */
public interface SecureKeyStore {

    void storeKey(String alias, byte[] key);

    Optional<byte[]> loadKey(String alias);

    void deleteKey(String alias);
}
