package com.vgu.client.models.network;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Low-level HTTP to the KIWI server: JSON in and out, the {@code {success, message, data}}
 * envelope unwrapped, Bearer token attached. Blocking; call it off the JavaFX thread.
 */
public final class ApiClient {

    public static final String DEFAULT_URL = "https://kiwi.lienminh.cc";

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    private final String baseUrl;
    private volatile String token;
    private volatile long clockOffsetMillis; // server time minus this machine's time

    /** A client for the server named by -Dkiwi.server, the KIWI_SERVER variable, or the default address. */
    public ApiClient() {
        this(addressFromEnvironment());
    }

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String addressFromEnvironment() {
        String url = System.getProperty("kiwi.server", System.getenv("KIWI_SERVER"));
        return url == null || url.isBlank() ? DEFAULT_URL : url;
    }

    /**
     * The current time according to the server. Expiry times (invite codes, tokens) are server
     * times, so they must be compared with this, not with the local clock, which may be off.
     * Accurate to about a second, learned from the {@code Date} header of every response.
     */
    public Instant serverNow() {
        return Instant.now().plusMillis(clockOffsetMillis);
    }

    /** Updates the clock offset from a response's Date header (which has whole-second resolution). */
    void observeServerDate(String dateHeader, long requestMidpointMillis) {
        if (dateHeader == null) {
            return;
        }
        try {
            long serverMillis = ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            // The header was truncated to the second, so the real time is half a second later on average.
            clockOffsetMillis = serverMillis + 500 - requestMidpointMillis;
        } catch (DateTimeParseException ignored) {
            // keep the previous offset
        }
    }

    private <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        long sent = System.currentTimeMillis();
        HttpResponse<T> response;
        try {
            response = http.send(request, handler);
        } catch (IOException e) {
            System.err.println("[api] " + request.method() + " " + request.uri().getPath() + " -> " + e);
            throw e;
        }
        long received = System.currentTimeMillis();
        // One line per request (never bodies or tokens), so it is easy to see what the client really sends.
        System.err.println("[api] " + request.method() + " " + request.uri().getPath() + " -> "
                + response.statusCode() + " (" + (received - sent) + " ms)");
        observeServerDate(response.headers().firstValue("Date").orElse(null), (sent + received) / 2);
        return response;
    }

    /** The WebSocket address for realtime push, e.g. {@code wss://host/ws?token=...}; null without a token. */
    public String webSocketUrl() {
        if (token == null) {
            return null;
        }
        String ws = baseUrl.startsWith("https://") ? "wss://" + baseUrl.substring(8)
                : baseUrl.startsWith("http://") ? "ws://" + baseUrl.substring(7) : baseUrl;
        return ws + "/ws?token=" + token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean hasToken() {
        return token != null;
    }

    /** Sends {@code body} (may be null) as JSON and returns the envelope's {@code data} as {@code type}. */
    public <T> T json(String method, String path, Object body, Class<T> type) throws ApiException {
        HttpRequest.Builder request = request(path).header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return unwrap(send(request.build()), type);
    }

    /** Uploads raw bytes with a single-use {@code Delivery} token (the one route that is not Bearer). */
    public <T> T upload(String path, String deliveryToken, byte[] bytes, Class<T> type) throws ApiException {
        HttpRequest request = request(path)
                .header("Authorization", "Delivery " + deliveryToken)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        return unwrap(send(request), type);
    }

    /** Downloads raw bytes (encrypted file blobs). */
    public byte[] download(String path) throws ApiException {
        HttpRequest.Builder request = request(path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<byte[]> response = exchange(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw failure(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
            }
            return response.body();
        } catch (IOException e) {
            throw new ApiException("Could not reach the server: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", e);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT);
    }

    private HttpResponse<String> send(HttpRequest request) throws ApiException {
        try {
            return exchange(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException("Could not reach the server: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", e);
        }
    }

    private <T> T unwrap(HttpResponse<String> response, Class<T> type) throws ApiException {
        if (response.statusCode() >= 400) {
            throw failure(response.statusCode(), response.body());
        }
        try {
            JsonObject envelope = gson.fromJson(response.body(), JsonObject.class);
            JsonElement data = envelope == null ? null : envelope.get("data");
            if (type == Void.class || data == null || data.isJsonNull()) {
                return null;
            }
            return gson.fromJson(data, type);
        } catch (JsonParseException e) {
            throw new ApiException(response.statusCode(), "Unexpected response from the server");
        }
    }

    private ApiException failure(int status, String body) {
        String message = null;
        try {
            JsonObject envelope = gson.fromJson(body, JsonObject.class);
            if (envelope != null && envelope.has("message") && !envelope.get("message").isJsonNull()) {
                message = envelope.get("message").getAsString();
            }
        } catch (JsonParseException ignored) {
            // not JSON (e.g. a proxy error page): fall back to the status text below
        }
        return new ApiException(status, message != null ? message : "Server error (HTTP " + status + ")");
    }
}
