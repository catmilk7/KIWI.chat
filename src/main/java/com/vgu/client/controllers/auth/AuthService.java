package com.vgu.client.controllers.auth;

import com.vgu.client.models.crypto.AESCryptoService;
import com.vgu.client.models.crypto.CryptoException;
import com.vgu.client.models.crypto.CryptoService;
import com.vgu.client.models.crypto.IdentityKeyPair;
import com.vgu.client.models.crypto.RsaPssSignatureService;
import com.vgu.client.models.crypto.SignatureService;
import com.vgu.client.models.network.ApiClient;
import com.vgu.client.models.network.ApiException;
import com.vgu.client.models.network.Dto;
import com.vgu.client.models.network.ServerApi;
import com.vgu.client.models.storage.AccountDao;
import com.vgu.client.models.storage.ChatDatabase;
import com.vgu.client.models.storage.DatabaseException;
import com.vgu.client.models.storage.KeyStoreException;
import com.vgu.client.models.storage.Pkcs12KeyStore;

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
        try (var dirs = java.nio.file.Files.list(baseDir)) {
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
        int bytes = new String(password).getBytes(StandardCharsets.UTF_8).length;
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
     * Registers on the server with the new public keys, then creates the local keystore
     * (identity keys + a random database key) and the encrypted chat database for the
     * server-assigned user id. Slow: generates RSA keys.
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
        try {
            IdentityKeyPair identity = IdentityKeyPair.generate(crypto, signatures);

            Dto.Auth auth;
            try {
                auth = api.register(username, new String(password), identity.encryptionPublicKeyBase64(),
                        identity.signingPublicKeyBase64());
            } catch (ApiException e) {
                throw serverProblem(e, "Could not register: ");
            }

            store.create();
            identity.saveTo(store);
            byte[] dbKey = new byte[ChatDatabase.KEY_BYTES];
            new SecureRandom().nextBytes(dbKey);
            store.storeKey(DB_KEY_ALIAS, dbKey);
            try (ChatDatabase db = ChatDatabase.open(databaseFile(username), dbKey)) {
                new AccountDao(db).create(username, auth.userId());
            } finally {
                Arrays.fill(dbKey, (byte) 0);
            }
            return identity;
        } catch (KeyStoreException | CryptoException | DatabaseException e) {
            throw new AuthException("Registered on the server, but could not create local data: " + e.getMessage());
        } finally {
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
                throw badCredentials();
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
        long userId = account.userId();
        boolean online = false;
        try {
            Dto.Auth auth = api.login(username, new String(password));
            if (userId != AccountDao.LOCAL_USER_ID && userId != auth.userId()) {
                throw new AuthException("This device's data belongs to a different server account.");
            }
            if (userId != auth.userId()) {
                accounts.bindUserId(auth.userId());
                userId = auth.userId();
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
        return new Session(account.username(), userId, identity, db, api, crypto, signatures, online);
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

    private Path databaseFile(String username) {
        return baseDir.resolve(username).resolve(DATABASE_NAME);
    }

    private Pkcs12KeyStore keyStoreFor(String username, char[] password) throws AuthException {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new AuthException("Username contains illegal characters.");
        }
        return new Pkcs12KeyStore(baseDir.resolve(username).resolve(KEYSTORE_NAME), password);
    }

    // Same message for unknown user and wrong password, so it does not reveal which usernames exist.
    private static AuthException badCredentials() {
        return new AuthException("Wrong username or password.");
    }
}
