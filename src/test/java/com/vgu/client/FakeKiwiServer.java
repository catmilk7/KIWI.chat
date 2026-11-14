package com.vgu.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.net.URLDecoder;

/**
 * A tiny in-memory stand-in for KIWI.Server, speaking the same envelope and status codes, so the
 * client can be tested without the network. Implements only what the client calls.
 */
public final class FakeKiwiServer implements AutoCloseable {

    private final Gson gson = new Gson();
    private final HttpServer http;
    private final Map<String, String> passwords = new HashMap<>();
    private final Map<String, Long> userIds = new HashMap<>();
    private final Map<Long, byte[]> files = new HashMap<>();
    private final Map<Long, String> groupMeta = new HashMap<>();
    private final Map<String, Long> inviteCodes = new HashMap<>();
    private final Map<Long, List<Long>> groupMembers = new HashMap<>();
    private final Map<Long, Long> groupEpochs = new HashMap<>();
    private final Map<String, Long> historyFloors = new HashMap<>();
    private final Map<Long, String[]> userKeys = new HashMap<>();
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private final AtomicLong messageIds = new AtomicLong(1);
    private final AtomicLong ids = new AtomicLong(1);
    private final List<String> registrationBodies = new ArrayList<>();
    private volatile String lastDeliveryToken;
    private int calls;

    public FakeKiwiServer() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", this::handle);
        http.start();
    }

    public String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    public synchronized int calls() { return calls; }

    /** Messages of one type the server has stored, e.g. how many SENDER_KEY packages were sent. */
    public synchronized long countMessages(String type) {
        return messages.stream().filter(m -> m.get("type").equals(type)).count();
    }

    /** Everything the server stored for messages, as one string: proves no plaintext reached it. */
    public synchronized String allStoredCiphertext() {
        StringBuilder all = new StringBuilder();
        for (Map<String, Object> m : messages) all.append(m.get("ciphertext")).append(m.get("iv"));
        return all.toString();
    }

    /** Damages the stored ciphertext of the newest message of a type, like a malicious server would. */
    public synchronized void corruptLast(String type) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).get("type").equals(type)) {
                String c = (String) messages.get(i).get("ciphertext");
                messages.get(i).put("ciphertext", (c.charAt(0) == 'A' ? "B" : "A") + c.substring(1));
                return;
            }
        }
    }
    public synchronized List<String> registrationBodies() { return List.copyOf(registrationBodies); }
    public synchronized byte[] storedFile(long id) { return files.get(id); }
    public synchronized String storedGroupMeta(long id) { return groupMeta.get(id); }

    @Override
    public void close() {
        http.stop(0);
    }

    private synchronized void handle(HttpExchange ex) throws IOException {
        calls++;
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        byte[] body = ex.getRequestBody().readAllBytes();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String text = new String(body, StandardCharsets.UTF_8);

        try {
            if (path.equals("/api/health")) {
                ok(ex, 200, Map.of("status", "ok"));
            } else if (path.equals("/api/auth/register") && method.equals("POST")) {
                registrationBodies.add(text);
                JsonObject json = gson.fromJson(text, JsonObject.class);
                String username = json.get("username").getAsString().trim();
                String password = json.get("password").getAsString();
                if (username.length() < 3 || username.length() > 100) {
                    fail(ex, 400, "Username must be between 3 and 100 characters");
                } else if (password.getBytes(StandardCharsets.UTF_8).length < 8) {
                    fail(ex, 400, "Password must be at least 8 UTF-8 bytes");
                } else if (passwords.containsKey(username)) {
                    fail(ex, 409, "Username already exists");
                } else {
                    passwords.put(username, password);
                    long newId = ids.getAndIncrement();
                    userIds.put(username, newId);
                    userKeys.put(newId, new String[]{json.get("encryptionPublicKey").getAsString(),
                            json.get("identitySigningPublicKey").getAsString()});
                    ok(ex, 201, authData(username));
                }
            } else if (path.equals("/api/auth/login") && method.equals("POST")) {
                JsonObject json = gson.fromJson(text, JsonObject.class);
                String username = json.get("username").getAsString();
                if (!json.get("password").getAsString().equals(passwords.get(username))) {
                    fail(ex, 401, "Invalid username or password");
                } else {
                    ok(ex, 200, authData(username));
                }
            } else if (path.equals("/api/auth/logout")) {
                ok(ex, 200, null);
            } else if (path.equals("/api/files/token") && method.equals("POST")) {
                if (auth == null || !auth.startsWith("Bearer ")) { fail(ex, 401, "Authentication required"); return; }
                lastDeliveryToken = "tok" + ids.getAndIncrement();
                ok(ex, 201, Map.of("token", lastDeliveryToken, "tokenType", "Delivery", "expiresAt", "2030-01-01T00:00:00Z"));
            } else if (path.equals("/api/files") && method.equals("POST")) {
                if (auth == null || !auth.equals("Delivery " + lastDeliveryToken)) { fail(ex, 401, "Invalid delivery token"); return; }
                lastDeliveryToken = null; // single use
                long id = ids.getAndIncrement();
                files.put(id, body);
                ok(ex, 201, Map.of("fileId", id, "size", body.length));
            } else if (path.startsWith("/api/files/") && method.equals("GET")) {
                byte[] blob = files.get(Long.parseLong(path.substring("/api/files/".length())));
                if (blob == null) { fail(ex, 404, "File not found"); return; }
                ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
                ex.sendResponseHeaders(200, blob.length);
                ex.getResponseBody().write(blob);
                ex.close();
            } else if (path.equals("/api/conversations/group") && method.equals("POST")) {
                long id = ids.getAndIncrement();
                long caller = callerId(auth);
                groupMeta.put(id, gson.fromJson(text, JsonObject.class).get("encryptedMeta").getAsString());
                groupMembers.put(id, new ArrayList<>(List.of(caller)));
                groupEpochs.put(id, 0L);
                ok(ex, 201, conversation(id, "GROUP", groupMembers.get(id), "OWNER", 0));
            } else if (path.matches("/api/conversations/\\d+/invites") && method.equals("POST")) {
                long id = Long.parseLong(path.split("/")[3]);
                String code = "7Q4M2X9TAB3C";
                inviteCodes.values().removeIf(v -> v == id); // minting revokes the previous code
                inviteCodes.put(code, id);
                ok(ex, 201, Map.of("inviteId", 1, "conversationId", id, "code", code, "expiresAt", "2030-01-01T00:00:00Z"));
            } else if (path.equals("/api/conversations/join") && method.equals("POST")) {
                String code = gson.fromJson(text, JsonObject.class).get("code").getAsString();
                if (!code.matches("[0-9A-HJKMNP-TV-Z]{12}")) { fail(ex, 400, "Invite code is invalid"); return; }
                Long id = inviteCodes.get(code);
                if (id == null) { fail(ex, 404, "Invite not found"); return; }
                long caller = callerId(auth);
                List<Long> members = groupMembers.get(id);
                if (!members.contains(caller)) {
                    members.add(caller);
                    groupEpochs.merge(id, 1L, Long::sum);
                    historyFloors.put(id + ":" + caller, messageIds.get() - 1); // new members never see older messages
                }
                ok(ex, 200, conversation(id, "GROUP", members, members.get(0) == caller ? "OWNER" : "MEMBER", groupEpochs.get(id)));
            } else if (path.matches("/api/conversations/\\d+/leave") && method.equals("POST")) {
                long id = Long.parseLong(path.split("/")[3]);
                long caller = callerId(auth);
                List<Long> members = groupMembers.get(id);
                if (members == null || !members.contains(caller)) { fail(ex, 403, "Not a member"); return; }
                if (members.get(0) == caller && members.size() > 1) { fail(ex, 403, "Owner cannot leave while others remain"); return; }
                members.remove(caller);
                if (members.isEmpty()) { groupMembers.remove(id); }
                else groupEpochs.merge(id, 1L, Long::sum);
                ok(ex, 200, Map.of("conversationId", id, "left", true));
            } else if (path.matches("/api/conversations/\\d+/members/\\d+") && method.equals("DELETE")) {
                String[] parts = path.split("/");
                long id = Long.parseLong(parts[3]);
                long target = Long.parseLong(parts[5]);
                List<Long> members = groupMembers.get(id);
                if (members == null || members.get(0) != callerId(auth)) { fail(ex, 403, "Owner only"); return; }
                if (!members.remove(target)) { fail(ex, 404, "Not a member"); return; }
                inviteCodes.values().removeIf(v -> v == id); // kicking revokes the active code
                groupEpochs.merge(id, 1L, Long::sum);
                ok(ex, 200, Map.of("conversationId", id, "memberIds", members, "epoch", groupEpochs.get(id), "removedUserId", target));
            } else if (path.matches("/api/conversations/\\d+") && method.equals("DELETE")) {
                long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                List<Long> members = groupMembers.get(id);
                if (members == null || members.get(0) != callerId(auth)) { fail(ex, 403, "Owner only"); return; }
                groupMembers.remove(id);
                ok(ex, 200, Map.of("conversationId", id, "deleted", true));
            } else if (path.equals("/api/conversations") && method.equals("GET")) {
                long caller = callerId(auth);
                List<Map<String, Object>> mine = new ArrayList<>();
                for (var entry : groupMembers.entrySet()) {
                    if (entry.getValue().contains(caller)) {
                        mine.add(conversation(entry.getKey(), "GROUP", entry.getValue(),
                                entry.getValue().get(0) == caller ? "OWNER" : "MEMBER", groupEpochs.get(entry.getKey())));
                    }
                }
                ok(ex, 200, mine);
            } else if (path.matches("/api/conversations/\\d+") && method.equals("GET")) {
                long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                long caller = callerId(auth);
                List<Long> members = groupMembers.get(id);
                if (members == null) { fail(ex, 404, "Conversation not found"); return; }
                if (!members.contains(caller)) { fail(ex, 403, "Not a member"); return; }
                ok(ex, 200, conversation(id, "GROUP", members, members.get(0) == caller ? "OWNER" : "MEMBER", groupEpochs.get(id)));
            } else if (path.matches("/api/keys/bundle/\\d+") && method.equals("GET")) {
                long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                String[] keys = userKeys.get(id);
                if (keys == null) { fail(ex, 404, "No key bundle"); return; }
                ok(ex, 200, Map.of("userId", id, "encryptionPublicKey", keys[0], "identitySigningPublicKey", keys[1],
                        "updatedAt", "2026-11-02T03:52:13Z"));
            } else if (path.equals("/api/messages") && method.equals("POST")) {
                long caller = callerId(auth);
                JsonObject json = gson.fromJson(text, JsonObject.class);
                long conversationId = json.get("conversationId").getAsLong();
                List<Long> members = groupMembers.get(conversationId);
                if (members == null) { fail(ex, 404, "Conversation not found"); return; }
                if (!members.contains(caller)) { fail(ex, 403, "Not a member"); return; }
                long epoch = json.has("epoch") && !json.get("epoch").isJsonNull() ? json.get("epoch").getAsLong() : -1;
                if (epoch < 0 || epoch > groupEpochs.get(conversationId)) { fail(ex, 409, "Epoch conflict"); return; }
                String type = json.get("messageType").getAsString();
                Long recipient = json.has("recipientId") && !json.get("recipientId").isJsonNull() ? json.get("recipientId").getAsLong() : null;
                if (type.equals("SENDER_KEY") != (recipient != null)) { fail(ex, 400, "recipientId rules violated"); return; }
                String clientMsgId = json.get("clientMsgId").getAsString();
                for (Map<String, Object> existing : messages) {
                    if (existing.get("conversationId").equals(conversationId) && existing.get("senderId").equals(caller)
                            && java.util.Objects.equals(existing.get("recipientId"), recipient)
                            && existing.get("clientMsgId").equals(clientMsgId)) {
                        ok(ex, 201, Map.of("messageId", existing.get("messageId"), "clientMsgId", clientMsgId,
                                "createdAt", existing.get("createdAt"), "duplicate", true));
                        return;
                    }
                }
                Map<String, Object> message = new HashMap<>();
                message.put("messageId", messageIds.getAndIncrement());
                message.put("conversationId", conversationId);
                message.put("senderId", caller);
                message.put("type", type);
                message.put("recipientId", recipient);
                message.put("epoch", epoch);
                message.put("ciphertext", json.get("ciphertext").getAsString());
                message.put("iv", json.get("iv").getAsString());
                message.put("clientMsgId", clientMsgId);
                message.put("createdAt", java.time.Instant.now().toString());
                message.put("sequence", message.get("messageId"));
                messages.add(message);
                ok(ex, 201, Map.of("messageId", message.get("messageId"), "clientMsgId", clientMsgId,
                        "createdAt", message.get("createdAt"), "duplicate", false));
            } else if (path.equals("/api/sync") && method.equals("GET")) {
                long caller = callerId(auth);
                Map<String, String> query = new HashMap<>();
                for (String pair : ex.getRequestURI().getRawQuery().split("&")) {
                    String[] kv = pair.split("=");
                    query.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
                long since = Long.parseLong(query.get("since"));
                int limit = Integer.parseInt(query.get("limit"));
                List<Map<String, Object>> visible = new ArrayList<>();
                for (Map<String, Object> m : messages) {
                    long id = (Long) m.get("messageId");
                    boolean member = groupMembers.get((Long) m.get("conversationId")).contains(caller);
                    boolean forMe = !m.get("type").equals("SENDER_KEY") || caller == (Long) m.get("recipientId");
                    long floor = historyFloors.getOrDefault(m.get("conversationId") + ":" + caller, 0L);
                    if (id > since && id > floor && member && forMe) visible.add(m);
                }
                boolean more = visible.size() > limit;
                List<Map<String, Object>> page = more ? visible.subList(0, limit) : visible;
                long next = page.isEmpty() ? since : (Long) page.get(page.size() - 1).get("messageId");
                Map<String, Object> data = new HashMap<>();
                data.put("messages", page);
                data.put("nextCursor", next);
                data.put("hasMore", more);
                ok(ex, 200, data);
            } else {
                fail(ex, 404, "Not found");
            }
        } catch (RuntimeException e) {
            fail(ex, 400, "Bad request");
        }
    }

    private long callerId(String auth) {
        return userIds.get(auth.substring("Bearer jwt-".length()));
    }

    private Map<String, Object> authData(String username) {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userIds.get(username));
        data.put("username", username);
        data.put("token", "jwt-" + username);
        data.put("tokenType", "Bearer");
        data.put("expiresAt", "2030-01-01T00:00:00Z");
        return data;
    }

    private Map<String, Object> conversation(long id, String type, List<Long> members, String role, long epoch) {
        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", id);
        data.put("type", type);
        data.put("encryptedMeta", "opaque");
        data.put("createdAt", "2026-11-02T03:52:13Z");
        data.put("memberIds", members);
        data.put("myRole", role);
        data.put("epoch", epoch);
        data.put("lastMsgId", 0);
        return data;
    }

    private void ok(HttpExchange ex, int status, Object data) throws IOException {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("success", true);
        envelope.put("message", null);
        envelope.put("data", data);
        write(ex, status, gson.toJson(envelope));
    }

    private void fail(HttpExchange ex, int status, String message) throws IOException {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("success", false);
        envelope.put("message", message);
        envelope.put("data", null);
        write(ex, status, gson.toJson(envelope));
    }

    private void write(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
