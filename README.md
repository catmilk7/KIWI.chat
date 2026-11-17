# KIWI.chat

Desktop client for **KIWI**, a small end-to-end encrypted (E2EE) messenger built for
the "OOP with Java" course at the Vietnamese-German University. The client does all
the cryptography; the [KIWI.Server](https://github.com/CallMeQan/KIWI.Server) only relays
and stores ciphertext and public keys. This is a learning project, not a production app.

> **Status: alpha.** Screens, the crypto layer with the keystore, the server client
> (REST and realtime), authentication, direct and group messaging logic, file sharing and
> the encrypted-at-rest local SQLite database are implemented and tested. The screens are not
> yet fully wired to the real services.

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
  ui/
    auth/                      login and register screen
    chat/                      main chat screen
    settings/                  user and group settings, confirm/error dialogs
    SceneNavigator, QuoteRotator
  service/                     AuthService, Session, MessagingService, GroupService, FileService, MessageCrypto
  model/                       Message, MessageContent, ConversationItem and their subclasses
  crypto/                      encryption, signatures, identity keys
  network/                     REST client (ServerApi), realtime client, DTOs
  storage/                     PKCS#12 keystore; SQLite local database and DAOs
  enums/                       ConversationType, MessageType, DeliveryStatus
src/main/resources/com/vgu/client/
  db/schema.sql                local database schema
  ui/views/                    FXML screens (auth, chat, settings)
  ui/assets/                   CSS, fonts, icons, images
src/test/java/                 JUnit 5 tests (includes a fake KIWI server)
```

## Cryptography

Every user has two RSA-3072 key pairs. The private halves never leave the user's computer.

| Purpose | Algorithm |
|---|---|
| Message content | AES-256-GCM, fresh random 96-bit nonce per message |
| Wrapping the AES key for a recipient | RSA-OAEP (SHA-256, MGF1-SHA-256) |
| Signing (identity) | RSA-PSS (SHA-256, 32-byte salt) |
| Key storage on disk | PKCS#12 file protected by the user's password |

Main classes (`com.vgu.client`):

- `crypto.CryptoService` / `AESCryptoService`: generate keys, encrypt and decrypt, wrap and unwrap AES keys.
- `crypto.SignatureService` / `RsaPssSignatureService`: sign and verify.
- `crypto.IdentityKeyPair`: the user's encryption and signing key pairs, saved to and loaded from the keystore.
- `crypto.KeyCodec`: RSA key generation, and conversion of keys to and from the Base64 text the server stores.
- `crypto.Sealed`: the result of encryption (nonce + ciphertext).
- `storage.SecureKeyStore` / `Pkcs12KeyStore`: store, load and delete secret bytes in a `.p12` file.

The rest of the app depends on the interfaces (`CryptoService`, `SignatureService`,
`SecureKeyStore`), so an algorithm or storage method can be replaced without touching chat logic.

## Tests

```bash
./gradlew test
```

| Test class | Covers |
|---|---|
| `CryptoAndKeyStoreTest` | encrypt/decrypt, nonce freshness, tamper rejection, wrong-key unwrap, signatures, key text length, keystore save/load and wrong password |
| `ChatDatabaseTest` | local database and DAOs |
| `AuthServiceTest` | register, login, logout against a fake server |
| `MessagingTest` | direct and group messaging (slow: generates many RSA-3072 keys) |
| `FilesAndGroupsTest` | file sharing and group management |

`FakeKiwiServer` is a small in-process server that stands in for KIWI.Server in tests.

## Roadmap

1. Wire the screens to the real services (login, chat list, sending and receiving)
2. Group key rotation when members change
3. Speed up `MessagingTest` by sharing generated keys
4. Report and admin features, if kept in the project scope

## Related

- Server: [KIWI.Server](https://github.com/CallMeQan/KIWI.Server), whose `expected_flow.md` describes the full message flow the client must follow.
