package com.vgu.client.controllers.auth;

import com.vgu.client.models.crypto.CryptoService;
import com.vgu.client.models.crypto.IdentityKeyPair;
import com.vgu.client.models.crypto.SignatureService;
import com.vgu.client.models.messaging.FileAttachment;
import com.vgu.client.models.messaging.GroupService;
import com.vgu.client.models.messaging.Message;
import com.vgu.client.models.messaging.MessageContent;
import com.vgu.client.models.messaging.MessageCrypto;
import com.vgu.client.models.messaging.MessagingService;
import com.vgu.client.models.network.ApiException;
import com.vgu.client.models.network.Dto;
import com.vgu.client.models.network.FileService;
import com.vgu.client.models.network.RealtimeClient;
import com.vgu.client.models.network.ServerEvent;
import com.vgu.client.models.network.ServerApi;
import com.vgu.client.models.storage.AccountDao;
import com.vgu.client.models.storage.ChatDatabase;
import com.vgu.client.models.storage.ContactDao;
import com.vgu.client.models.storage.ConversationDao;
import com.vgu.client.models.storage.GroupKeyDao;
import com.vgu.client.models.storage.MessageDao;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Everything that exists while someone is logged in: who they are, their identity keys, their
 * open encrypted database and their connection to the server. The UI gets data only through
 * the objects here. {@link #close()} on logout.
 */
public final class Session implements AutoCloseable {

    private static Session current;

    private final String username;
    private final long userId;
    private final IdentityKeyPair identity;
    private final ChatDatabase database;
    private final ServerApi api;
    private final boolean online;
    private final AccountDao account;
    private final ConversationDao conversations;
    private final MessageDao messages;
    private final ContactDao contacts;
    private final GroupService groups;
    private final FileService files;
    private final MessagingService messaging;
    private final RealtimeClient realtime;
    private final ServerEvent.Sink eventSink;
    // One thread does all protocol work (send, receive, sync), so sender keys and cursors never race.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "messaging");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.ScheduledExecutorService poller =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "poller");
                thread.setDaemon(true);
                return thread;
            });
    private volatile Runnable changeListener = () -> { };

    Session(String username, long userId, IdentityKeyPair identity, ChatDatabase database, ServerApi api,
            CryptoService crypto, SignatureService signatures, boolean online) {
        this.username = username;
        this.userId = userId;
        this.identity = identity;
        this.database = database;
        this.api = api;
        this.online = online;
        this.account = new AccountDao(database);
        this.conversations = new ConversationDao(database, userId);
        this.messages = new MessageDao(database, userId);
        this.contacts = new ContactDao(database);
        this.groups = new GroupService(api, crypto, conversations, contacts, new GroupKeyDao(database), userId, username);
        this.files = new FileService(api, crypto);
        GroupKeyDao groupKeys = new GroupKeyDao(database);
        this.messaging = new MessagingService(api, new MessageCrypto(crypto, signatures, identity), groups, account,
                conversations, messages, contacts, groupKeys, identity.encryption().getPrivate(), userId, username);
        this.eventSink = new ServerEvent.Sink() {
            @Override public void messageArrived(Dto.SyncMessage message) {
                submit(() -> messaging.handleIncoming(message));
            }
            @Override public void conversationsChanged() {
                submit(messaging::refreshAndAnnounce);
            }
            @Override public void conversationRemoved(long conversationId) {
                submit(() -> conversations.delete(conversationId));
            }
        };
        this.realtime = new RealtimeClient(api.client(), new RealtimeClient.Listener() {
            @Override public void onConnected() {
                submit(() -> { messaging.retryPending(); messaging.syncAll(); });
            }
            @Override public void onDisconnected() {
                changeListener.run();
            }
            @Override public void onEvent(ServerEvent event) {
                event.deliverTo(eventSink); // each kind of event calls the matching reaction itself
            }
        });
    }

    /** Starts realtime push and a first sync. Called once, when this becomes the active session. */
    private void activate() {
        if (online) {
            submit(() -> { messaging.retryPending(); messaging.syncAll(); });
            realtime.start();
            // Push is best-effort (see the server docs). Catching up every 30 s means being kicked, or a missed
            // message, shows up even if the WebSocket is down. The UI is only told when something changed.
            poller.scheduleWithFixedDelay(() -> {
                if (worker.isShutdown()) return;
                worker.execute(() -> {
                    try {
                        if (messaging.syncAll()) changeListener.run();
                    } catch (ApiException e) {
                        // offline: try again at the next tick
                    } catch (RuntimeException e) {
                        System.err.println("[msg] unexpected: " + e);
                    }
                });
            }, 30, 30, TimeUnit.SECONDS);
        }
    }

    /** Runs protocol work on the messaging thread, then tells the UI something may have changed. */
    private void submit(ProtocolWork work) {
        if (worker.isShutdown()) {
            return;
        }
        worker.execute(() -> {
            try {
                work.run();
            } catch (ApiException e) {
                System.err.println("[msg] " + (e.isNetworkError() ? "offline: " : "server said: ") + e.getMessage());
            } catch (RuntimeException e) {
                System.err.println("[msg] unexpected: " + e);
            }
            changeListener.run();
        });
    }

    @FunctionalInterface
    private interface ProtocolWork {
        void run() throws ApiException;
    }

    /** Stores a message of any kind (shown at once as SENDING) and sends it in the background. */
    public Message send(long conversationId, MessageContent content) {
        Message message = messages.insertOutgoing(conversationId, content);
        transmitLater(message);
        return message;
    }

    /** Same for an attachment message whose file is already uploaded. */
    public Message sendFile(long conversationId, FileAttachment file) {
        Message message = messages.insertOutgoingFile(conversationId, file);
        transmitLater(message);
        return message;
    }

    private void transmitLater(Message message) {
        if (online) {
            submit(() -> messaging.transmit(message));
        }
    }

    /** Called (on a background thread) whenever messages, conversations or the connection changed. */
    public void onChange(Runnable listener) {
        this.changeListener = listener == null ? () -> { } : listener;
    }


    /** Makes {@code session} the active one, closing any previous session. */
    public static synchronized void start(Session session) {
        if (current != null) {
            current.close();
        }
        current = Objects.requireNonNull(session);
        session.activate();
    }

    public static synchronized Session current() {
        if (current == null) {
            throw new IllegalStateException("Nobody is logged in");
        }
        return current;
    }

    public static synchronized void end() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    /** Current time according to the server (see {@link ApiClient#serverNow()}); use it for expiry checks. */
    public java.time.Instant serverNow() { return api.client().serverNow(); }
    public String username() { return username; }
    public String handle() { return "@" + username; }
    public long userId() { return userId; }
    /** False when the server could not be reached at login: local data only, nothing can be sent. */
    public boolean online() { return online; }
    public IdentityKeyPair identity() { return identity; }
    public AccountDao account() { return account; }
    public ConversationDao conversations() { return conversations; }
    public MessageDao messages() { return messages; }
    public ContactDao contacts() { return contacts; }
    public GroupService groups() { return groups; }
    public MessagingService messaging() { return messaging; }
    public FileService files() { return files; }

    @Override
    public void close() {
        poller.shutdownNow();
        realtime.close();
        worker.shutdownNow();
        try {
            worker.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (online && api.client().hasToken()) {
            Thread logout = new Thread(() -> {
                try {
                    api.logout();
                } catch (ApiException ignored) {
                    // the token simply expires on the server
                }
            }, "logout");
            logout.setDaemon(true);
            logout.start();
        }
        database.close();
    }
}
