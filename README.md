# KIWI.chat

Desktop client for **KIWI**, a small end-to-end encrypted (E2EE) messenger built for
the "OOP with Java" course at the Vietnamese-German University. The client does all
the cryptography; the [KIWI.Server](https://github.com/CallMeQan/KIWI.Server) only relays
and stores ciphertext and public keys. This is a learning project, not a production app.

> **Status: early alpha.** The screens (login, register, chat, settings) and the crypto layer
> with the keystore are done. Networking, real chats, groups, the local database and file
> sharing are still to do.

## Requirements

- JDK 25
- Nothing else: Gradle is downloaded by the wrapper (`gradlew`), and JavaFX 21 comes in as a Gradle dependency.

## Build, test, run

```bash
./gradlew build     # compile + run tests
./gradlew test      # tests only
./gradlew run       # start the app
```

On Windows use `gradlew.bat`. On Linux or macOS, if you get "permission denied", run
`sh ./gradlew ...` or `chmod +x gradlew` once.

## Project layout

```
src/main/java/com/vgu/client/
  Main.java                    JavaFX entry point (loads fonts, theme, login screen)
  models/
    crypto/                    encryption, signatures, identity keys (done)
    storage/                   password-protected key storage (done); local database (placeholder)
    identity/, messaging/      User, Profile, Contact, Conversation, Message ...
    network/, security/        server client, key bundles, sender keys (placeholders)
  controllers/
    auth/                      login and register screen logic
    central/                   chat, group and user settings screens
    utils/                     helpers (JWT parsing)
  enums/                       ConversationType, MemberRole, MessageType, ...
  database_dummy/              temporary fake data for the UI
src/main/resources/com/vgu/client/ui/
  views/                       FXML screens (auth, chat, settings)
  assets/                      CSS, fonts, icons, images
src/test/java/                 JUnit 5 tests
```

## Cryptography

Every user has two RSA-3072 key pairs. The private halves never leave the user's computer.

| Purpose | Algorithm |
|---|---|
| Message content | AES-256-GCM, fresh random 96-bit nonce per message |
| Wrapping the AES key for a recipient | RSA-OAEP (SHA-256, MGF1-SHA-256) |
| Signing (identity) | RSA-PSS (SHA-256, 32-byte salt) |
| Key storage on disk | PKCS#12 file protected by the user's password |

Main classes (`com.vgu.client.models`):

- `crypto.CryptoService` / `AESCryptoService`: generate keys, encrypt and decrypt, wrap and unwrap AES keys.
- `crypto.SignatureService` / `RsaPssSignatureService`: sign and verify.
- `crypto.IdentityKeyPair`: the user's encryption and signing key pairs, saved to and loaded from the keystore.
- `crypto.KeyCodec`: RSA key generation, and conversion of keys to and from the Base64 text the server stores.
- `crypto.Sealed`: the result of encryption (nonce + ciphertext).
- `storage.SecureKeyStore` / `Pkcs12KeyStore`: store, load and delete secret bytes in a `.p12` file.

The rest of the app depends on the interfaces (`CryptoService`, `SignatureService`,
`SecureKeyStore`), so an algorithm or storage method can be replaced without touching chat logic.

## Tests

`CryptoAndKeyStoreTest` checks, among other things: encrypt/decrypt round trips, a new
nonce every time, rejection of tampered data, wrong-key unwrapping, signature
verification, key text fitting the server's length limit, and keystore save/load with a
wrong password.

## Roadmap

1. Server client and authentication (register, login, logout)
2. Direct chats: fetch a contact's keys, encrypt, sign, send and receive
3. Group chats: sender keys and key rotation when members change
4. Encrypted local database, contacts and file sharing
5. Chat user interface

## Related

- Server: [KIWI.Server](https://github.com/CallMeQan/KIWI.Server), whose `expected_flow.md` describes the full message flow the client must follow.
