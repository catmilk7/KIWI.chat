package com.vgu.client.model.storage;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Optional;

/**
 * {@link SecureKeyStore} backed by a password-protected PKCS#12 file. Each
 * value is saved as a secret-key entry, so it is encrypted on disk with a key
 * derived from the user's password. Writes go to a temp file first and then
 * replace the original, so a crash cannot corrupt the keystore.
 */
public final class Pkcs12KeyStore implements SecureKeyStore {

    private final Path file;
    private final char[] password;

    public Pkcs12KeyStore(Path file, char[] password) {
        this.file = file;
        this.password = password.clone();
    }

    public boolean exists() {
        return Files.exists(file);
    }

    /** Creates an empty keystore. Fails if the file exists. */
    public void create() {
        if (exists()) {
            throw new KeyStoreException("Keystore already exists", null);
        }
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, password);
            save(ks);
        } catch (GeneralSecurityException | IOException e) {
            throw new KeyStoreException("Could not create keystore", e);
        }
    }

    /** Opens the file once to check the password; throws if it is wrong. */
    public void verifyPassword() {
        open();
    }

    @Override
    public synchronized void storeKey(String alias, byte[] key) {
        try {
            KeyStore ks = open();
            ks.setEntry(alias, new KeyStore.SecretKeyEntry(new SecretKeySpec(key, "AES")),
                    new KeyStore.PasswordProtection(password));
            save(ks);
        } catch (GeneralSecurityException | IOException e) {
            throw new KeyStoreException("Could not store key '" + alias + "'", e);
        }
    }

    @Override
    public synchronized Optional<byte[]> loadKey(String alias) {
        try {
            KeyStore ks = open();
            if (!ks.containsAlias(alias)) {
                return Optional.empty();
            }
            return Optional.of(ks.getKey(alias, password).getEncoded());
        } catch (GeneralSecurityException e) {
            throw new KeyStoreException("Could not load key '" + alias + "'", e);
        }
    }

    @Override
    public synchronized void deleteKey(String alias) {
        try {
            KeyStore ks = open();
            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias);
                save(ks);
            }
        } catch (GeneralSecurityException | IOException e) {
            throw new KeyStoreException("Could not delete key '" + alias + "'", e);
        }
    }

    /** Overwrites the password copy in memory. Call on logout. */
    public void close() {
        Arrays.fill(password, '\0');
    }

    private KeyStore open() {
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            return ks;
        } catch (IOException | GeneralSecurityException e) {
            throw new KeyStoreException("Could not open keystore (wrong password or corrupt file)", e);
        }
    }

    private void save(KeyStore ks) throws IOException, GeneralSecurityException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "keystore", ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            ks.store(out, password);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
