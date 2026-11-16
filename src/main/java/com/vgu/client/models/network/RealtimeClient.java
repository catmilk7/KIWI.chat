package com.vgu.client.models.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The WebSocket to the server ({@code /ws?token=...}): realtime push of messages and conversation
 * changes. Reconnects with backoff, and tells the listener on every (re)connect so it can run a
 * sync to catch up on anything missed while disconnected. Push is best-effort; sync is the truth.
 */
public final class RealtimeClient implements AutoCloseable {

    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onEvent(ServerEvent event);
    }

    private static final int POLICY_VIOLATION = 1008; // revoked or expired session: do not retry

    private final ApiClient client;
    private final Listener listener;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "realtime");
        thread.setDaemon(true);
        return thread;
    });
    private volatile WebSocket socket;
    private volatile boolean closed;
    private int attempt;

    public RealtimeClient(ApiClient client, Listener listener) {
        this.client = client;
        this.listener = listener;
    }

    public void start() {
        scheduler.execute(this::connect);
        scheduler.scheduleAtFixedRate(this::ping, 25, 25, TimeUnit.SECONDS); // the server drops idle sockets after 60 s
    }

    private void connect() {
        String url = client.webSocketUrl();
        if (closed || url == null) {
            return;
        }
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), new Handler())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        System.err.println("[ws] connect failed: " + error.getClass().getSimpleName());
                        reconnectLater();
                    }
                });
    }

    private void ping() {
        WebSocket current = socket;
        if (current != null) {
            current.sendText("{\"type\":\"PING\"}", true);
        }
    }

    private void reconnectLater() {
        if (closed) {
            return;
        }
        long delay = Math.min(30, 1L << Math.min(attempt++, 5));
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    /** Turns one server envelope into an event for the listener (public so tests can feed it envelopes). */
    public void dispatch(String json) {
        try {
            ServerEvent event = ServerEvent.parse(gson.fromJson(json, JsonObject.class));
            if (event != null) {
                listener.onEvent(event);
            }
        } catch (JsonParseException | NullPointerException | IllegalStateException | NumberFormatException e) {
            System.err.println("[ws] ignored a malformed envelope");
        }
    }

    private final class Handler implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            attempt = 0;
            System.err.println("[ws] connected");
            webSocket.request(1);
            listener.onConnected();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                dispatch(json);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.err.println("[ws] closed " + statusCode);
            socket = null;
            listener.onDisconnected();
            if (statusCode != POLICY_VIOLATION) {
                reconnectLater();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("[ws] error " + error.getClass().getSimpleName());
            socket = null;
            listener.onDisconnected();
            reconnectLater();
        }
    }

    @Override
    public void close() {
        closed = true;
        WebSocket current = socket;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        scheduler.shutdownNow();
    }
}
