package com.vgu.client.models.network;

import java.util.LinkedHashMap;
import java.util.Map;

/** One method per KIWI server endpoint the client uses. Blocking; call off the JavaFX thread. */
public final class ServerApi {

    private final ApiClient client;

    /** An API on a client pointed at the default server. */
    public ServerApi() {
        this(new ApiClient());
    }

    public ServerApi(ApiClient client) {
        this.client = client;
    }

    public ApiClient client() {
        return client;
    }

    // ---- auth ----

    public Dto.Auth register(String username, String password, String encryptionPublicKey,
                             String identitySigningPublicKey) throws ApiException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("encryptionPublicKey", encryptionPublicKey);
        body.put("identitySigningPublicKey", identitySigningPublicKey);
        Dto.Auth auth = client.json("POST", "/api/auth/register", body, Dto.Auth.class);
        client.setToken(auth.token());
        return auth;
    }

    public Dto.Auth login(String username, String password) throws ApiException {
        Dto.Auth auth = client.json("POST", "/api/auth/login",
                Map.of("username", username, "password", password), Dto.Auth.class);
        client.setToken(auth.token());
        return auth;
    }

    public void logout() throws ApiException {
        try {
            client.json("POST", "/api/auth/logout", null, Void.class);
        } finally {
            client.setToken(null);
        }
    }

    public boolean healthy() {
        try {
            client.json("GET", "/api/health", null, Object.class);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    // ---- keys ----

    public Dto.KeyBundle keyBundle(long userId) throws ApiException {
        return client.json("GET", "/api/keys/bundle/" + userId, null, Dto.KeyBundle.class);
    }

    // ---- conversations ----

    public Dto.Conversation[] conversations() throws ApiException {
        return client.json("GET", "/api/conversations", null, Dto.Conversation[].class);
    }

    public Dto.Conversation conversation(long conversationId) throws ApiException {
        return client.json("GET", "/api/conversations/" + conversationId, null, Dto.Conversation.class);
    }

    public Dto.Conversation createGroup(String encryptedMeta) throws ApiException {
        return client.json("POST", "/api/conversations/group", Map.of("encryptedMeta", encryptedMeta),
                Dto.Conversation.class);
    }

    public Dto.Invite mintInvite(long conversationId, int expiresInMinutes) throws ApiException {
        return client.json("POST", "/api/conversations/" + conversationId + "/invites",
                Map.of("expiresInMinutes", expiresInMinutes), Dto.Invite.class);
    }

    public Dto.Conversation joinByInvite(String code) throws ApiException {
        return client.json("POST", "/api/conversations/join", Map.of("code", code), Dto.Conversation.class);
    }

    public Dto.Conversation addMember(long conversationId, long userId) throws ApiException {
        return client.json("POST", "/api/conversations/" + conversationId + "/members", Map.of("userId", userId),
                Dto.Conversation.class);
    }

    public void kickMember(long conversationId, long userId) throws ApiException {
        client.json("DELETE", "/api/conversations/" + conversationId + "/members/" + userId, null, Object.class);
    }

    public void leaveGroup(long conversationId) throws ApiException {
        client.json("POST", "/api/conversations/" + conversationId + "/leave", null, Object.class);
    }

    public void deleteGroup(long conversationId) throws ApiException {
        client.json("DELETE", "/api/conversations/" + conversationId, null, Object.class);
    }

    // ---- messages ----

    public Dto.SendResult sendMessage(long conversationId, String ciphertext, String iv, String messageType,
                                      String clientMsgId, Long epoch, Long recipientId) throws ApiException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("ciphertext", ciphertext);
        body.put("iv", iv);
        body.put("messageType", messageType);
        body.put("clientMsgId", clientMsgId);
        body.put("epoch", epoch);
        body.put("recipientId", recipientId);
        return client.json("POST", "/api/messages", body, Dto.SendResult.class);
    }

    /** One page of the global stream of everything newer than {@code since} (exclusive). */
    public Dto.MessagePage sync(long since, int limit) throws ApiException {
        return client.json("GET", "/api/sync?since=" + since + "&limit=" + limit, null, Dto.MessagePage.class);
    }

    // ---- files (encrypted blobs) ----

    public Dto.DeliveryToken mintUploadToken() throws ApiException {
        return client.json("POST", "/api/files/token", null, Dto.DeliveryToken.class);
    }

    public Dto.FileUpload uploadFile(String deliveryToken, byte[] encryptedBlob) throws ApiException {
        return client.upload("/api/files", deliveryToken, encryptedBlob, Dto.FileUpload.class);
    }

    public byte[] downloadFile(long fileId) throws ApiException {
        return client.download("/api/files/" + fileId);
    }
}
