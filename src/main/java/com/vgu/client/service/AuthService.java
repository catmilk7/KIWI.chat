package com.vgu.client.service;

import com.vgu.client.crypto.AESCryptoService;
import com.vgu.client.crypto.CryptoException;
import com.vgu.client.crypto.CryptoService;
import com.vgu.client.crypto.IdentityKeyPair;
import com.vgu.client.crypto.RsaPssSignatureService;
import com.vgu.client.crypto.SignatureService;
import com.vgu.client.network.ApiClient;
import com.vgu.client.network.ApiException;
import com.vgu.client.network.Dto;
import com.vgu.client.network.ServerApi;
import com.vgu.client.storage.AccountDao;
import com.vgu.client.storage.AccountPassword;
import com.vgu.client.storage.ChatDatabase;
import com.vgu.client.storage.DatabaseException;
import com.vgu.client.storage.KeyStoreException;
import com.vgu.client.storage.Pkcs12KeyStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Register and login. The server owns the account (username, password hash, public keys, JWT);
 * the local PKCS#12 keystore owns the private keys and the database key. Blocking: call off the
 * JavaFX thread. Contains no JavaFX code.
 */
public class AuthService {

    // Same limits the server enforces (AuthValidator), plus a stricter character set.
    static final int MIN_USERNAME = 3;
    static final int MAX_USERNAME = 100;
    static final int MIN_PASSWORD_BYTES = 8;
    static final int MAX_PASSWORD_BYTES = 72;

    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9_]+");
    private static final String KEYSTORE_NAME = "keystore.p12";
    private static final String DATABASE_NAME = "chat.db";
    private static final String DB_KEY_ALIAS = "db.key";

    private final Path baseDir;
    private final CryptoService crypto;
    private final SignatureService signatures;
    private final ServerApi api;

    public AuthService() {
        this(Path.of(System.getProperty("user.home"), ".kiwi"), new AESCryptoService(), new RsaPssSignatureService(),
                new ServerApi());
    }

    public AuthService(Path baseDir, CryptoService crypto, SignatureService signatures, ServerApi api) {
        this.baseDir = baseDir;
        this.crypto = crypto;
        this.signatures = signatures;
        this.api = api;
    }

    /** Accounts that have local data on this device, i.e. the ones this machine can switch to. */
    public java.util.List<String> localAccounts() {
        if (!java.nio.file.Files.isDirectory(baseDir)) {
            return java.util.List.of();
        }
        try (java.util.stream.Stream<Path> dirs = java.nio.file.Files.list(baseDir)) {
            return dirs.filter(d -> java.nio.file.Files.exists(d.resolve(KEYSTORE_NAME)))
                    .map(d -> d.getFileName().toString())
                    .filter(name -> USERNAME.matcher(name).matches())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (java.io.IOException e) {
            return java.util.List.of();
        }
    }

    public static boolean isValidUsername(String username) {
        return username != null && username.length() >= MIN_USERNAME && username.length() <= MAX_USERNAME
                && USERNAME.matcher(username).matches();
    }

    public static boolean isValidPassword(char[] password) {
        int bytes = AccountPassword.normalized(password).getBytes(StandardCharsets.UTF_8).length;
        return bytes >= MIN_PASSWORD_BYTES && bytes <= MAX_PASSWORD_BYTES;
    }

    /** Message explaining why a registration input is rejected, or null if it is fine. */
    public static String registrationProblem(String username, char[] password) {
        if (!isValidUsername(username)) {
            return "Username must be " + MIN_USERNAME + "-" + MAX_USERNAME
                    + " characters: letters, digits and underscore only.";
        }
        if (!isValidPassword(password)) {
            return "Password must be " + MIN_PASSWORD_BYTES + "-" + MAX_PASSWORD_BYTES + " bytes long.";
        }
        return null;
    }

    /**
     * Creates the local keystore (identity keys + a random database key) first, then registers on the server
     * with the new public keys, then creates the encrypted chat database for the server-assigned user id.
     * Local keys come first on purpose: if they cannot be made, nothing has reached the server, so there is
     * never an account on the server whose keys do not exist. Slow: generates RSA keys.
     */
    public IdentityKeyPair register(String username, char[] password) throws AuthException {
        String problem = registrationProblem(username, password);
        if (problem != null) {
            throw new AuthException(problem);
        }
        Pkcs12KeyStore store = keyStoreFor(username, password);
        if (store.exists()) {
            store.close();
            throw new AuthException("This username is already registered on this device.");
        }
        byte[] dbKey = new byte[ChatDatabase.KEY_BYTES];
        try {
            IdentityKeyPair identity = IdentityKeyPair.generate(crypto, signatures);
            store.create();
            identity.saveTo(store);
            new SecureRandom().nextBytes(dbKey);
            store.storeKey(DB_KEY_ALIAS, dbKey);

            Dto.Auth auth;
            try {
                auth = api.register(username, AccountPassword.normalized(password), identity.encryptionPublicKeyBase64(),
                        identity.signingPublicKeyBase64());
            } catch (ApiException e) {
                discardLocalData(username); // the server refused (name taken, no connection...): leave nothing behind
                throw serverProblem(e, "Could not register: ");
            }

            try (ChatDatabase db = ChatDatabase.open(databaseFile(username), dbKey)) {
                new AccountDao(db).create(username, auth.getUserId());
            } catch (DatabaseException e) {
                throw new AuthException("Registered on the server, but could not create the local database: " + e.getMessage());
            }
            return identity;
        } catch (KeyStoreException | CryptoException e) {
            discardLocalData(username);
            throw new AuthException("Could not create your keys on this device: " + e.getMessage());
        } finally {
            Arrays.fill(dbKey, (byte) 0);
            store.close();
        }
    }

    /**
     * Unlocks the local keystore and database with the password, then logs in to the server for
     * a JWT. If the server cannot be reached the session still opens, offline.
     */
    public Session login(String username, char[] password) throws AuthException {
        Pkcs12KeyStore store = keyStoreFor(username, password);
        try {
            if (!store.exists()) {
                // Not a password problem. The private keys never leave the device that registered the account.
                throw new AuthException("This device has no keys for @" + username + ". An account's keys stay on the "
                        + "device that registered it: log in there, or copy that device's folder "
                        + baseDir.resolve(username) + " to this one.");
            }
            store.verifyPassword();
            IdentityKeyPair identity = IdentityKeyPair.loadFrom(store)
                    .orElseThrow(() -> new AuthException("Your key data is incomplete."));
            byte[] dbKey = store.loadKey(DB_KEY_ALIAS)
                    .orElseThrow(() -> new AuthException("Your key data is incomplete."));
            try {
                ChatDatabase db = ChatDatabase.open(databaseFile(username), dbKey);
                try {
                    return openSession(db, identity, username, password);
                } catch (AuthException | RuntimeException e) {
                    db.close();
                    throw e;
                }
            } finally {
                Arrays.fill(dbKey, (byte) 0);
            }
        } catch (KeyStoreException e) {
            throw badCredentials();
        } catch (DatabaseException e) {
            throw new AuthException("Could not open your chat database: " + e.getMessage());
        } finally {
            store.close();
        }
    }

    private Session openSession(ChatDatabase db, IdentityKeyPair identity, String username, char[] password)
            throws AuthException {
        AccountDao accounts = new AccountDao(db);
        AccountDao.Account account = accounts.get();
        long userId = account.getUserId();
        boolean online = false;
        try {
            Dto.Auth auth = api.login(username, AccountPassword.normalized(password));
            if (userId != AccountDao.LOCAL_USER_ID && userId != auth.getUserId()) {
                throw new AuthException("This device's data belongs to a different server account.");
            }
            if (userId != auth.getUserId()) {
                accounts.bindUserId(auth.getUserId());
                userId = auth.getUserId();
            }
            online = true;
        } catch (ApiException e) {
            if (e.isNetworkError()) {
                api.client().setToken(null); // offline: local data still opens, nothing can be sent yet
            } else if (e.status() == 401) {
                throw badCredentials();
            } else {
                throw serverProblem(e, "Could not log in: ");
            }
        }
        return new Session(account.getUsername(), userId, identity, db, api, crypto, signatures, online);
    }

    private static AuthException serverProblem(ApiException e, String prefix) {
        if (e.isNetworkError()) {
            return new AuthException("Cannot reach the server. Check your connection and try again.");
        }
        if (e.status() == 409) {
            return new AuthException("That username is already taken.");
        }
        return new AuthException(prefix + e.getMessage());
    }

    /** Removes whatever a failed registration created on this device (keystore, database files, the folder). */
    private void discardLocalData(String username) {
        Path folder = baseDir.resolve(username);
        try {
            for (String name : new String[]{KEYSTORE_NAME, DATABASE_NAME, DATABASE_NAME + "-wal", DATABASE_NAME + "-shm"}) {
                java.nio.file.Files.deleteIfExists(folder.resolve(name));
            }
            java.nio.file.Files.deleteIfExists(folder); // only succeeds when empty
        } catch (java.io.IOException ignored) {
            // best effort: a leftover folder only blocks registering the same name again on this device
        }
    }

    private Path databaseFile(String username) {
        return baseDir.resolve(username).resolve(DATABASE_NAME);
    }

    private Pkcs12KeyStore keyStoreFor(String username, char[] password) throws AuthException {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new AuthException("Username contains illegal characters.");
        }
        return new Pkcs12KeyStore(baseDir.resolve(username).resolve(KEYSTORE_NAME),
                AccountPassword.forKeystore(password, username));
    }

    // Same message for unknown user and wrong password, so it does not reveal which usernames exist.
    private static AuthException badCredentials() {
        return new AuthException("Wrong username or password.");
    }
}
