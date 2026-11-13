# KIWI.chat

Desktop client for **KIWI**, a small end-to-end encrypted (E2EE) messenger built for
the "OOP with Java" course at the Vietnamese-German University. The client does all
the cryptography; the [KIWI.Server](https://github.com/CallMeQan/KIWI.Server) only relays
and stores ciphertext and public keys. This is a learning project, not a production app.

> **Status: early alpha.** Only the login and register screens exist so far.

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
  ui/controllers/auth/         login and register screen logic
src/main/resources/com/vgu/client/ui/
  views/                       FXML screens
  assets/                      CSS, fonts, icons, images
src/test/java/                 JUnit 5 tests
```

## Branches

| Branch | Content |
|---|---|
| `master` | Stable base: project setup, README, CI |
| `view` | JavaFX screens (chat, settings) and the models behind them |
| `crypto-layer` | Encryption, signatures and the password-protected keystore |

## Roadmap

1. Server client and authentication (register, login, logout)
2. Direct chats: fetch a contact's keys, encrypt, sign, send and receive
3. Group chats: sender keys and key rotation when members change
4. Encrypted local database, contacts and file sharing
5. Chat user interface

## Related

- Server: [KIWI.Server](https://github.com/CallMeQan/KIWI.Server), whose `expected_flow.md` describes the full message flow the client must follow.
