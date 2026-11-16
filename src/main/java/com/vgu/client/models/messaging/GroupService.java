package com.vgu.client.models.messaging;

import com.google.gson.Gson;
import com.vgu.client.enums.ConversationType;
import com.vgu.client.models.crypto.CryptoService;
import com.vgu.client.models.crypto.Sealed;
import com.vgu.client.models.network.ApiException;
import com.vgu.client.models.network.Dto;
import com.vgu.client.models.network.ServerApi;
import com.vgu.client.models.storage.ContactDao;
import com.vgu.client.models.storage.ConversationDao;
import com.vgu.client.models.storage.GroupKeyDao;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Group conversations: the server stores only an opaque {@code encryptedMeta}, so the client
 * seals the group name with a fresh AES key (kept locally as the owner's epoch sender key) and
 * the server never sees the name. Invite codes are minted and redeemed on the server.
 * Blocking: call off the JavaFX thread.
 *
 * <p>Every method that changes the local conversations is {@code synchronized}. The server pushes a
 * "conversation changed" event the instant someone joins, and the client re-syncs on a timer, so a
 * refresh runs beside the user's own create/join/leave. Without the lock the refresh could apply a
 * list it fetched <em>before</em> the user's action and delete (or resurrect) the group.
 */
public final class GroupService {

    public static final int DEFAULT_INVITE_MINUTES = 1440;
    private static final byte[] META_AAD = "kiwi.group.meta".getBytes(StandardCharsets.UTF_8);

    private final ServerApi api;
    private final CryptoService crypto;
    private final ConversationDao conversations;
    private final ContactDao contacts;
    private final GroupKeyDao groupKeys;
    private final long me;
    private final String myUsername;
    private final Gson gson = new Gson();

    public GroupService(ServerApi api, CryptoService crypto, ConversationDao conversations, ContactDao contacts,
                        GroupKeyDao groupKeys, long me, String myUsername) {
        this.api = api;
        this.crypto = crypto;
        this.conversations = conversations;
        this.contacts = contacts;
        this.groupKeys = groupKeys;
        this.me = me;
        this.myUsername = myUsername;
    }

    /** Creates a group on the server and stores it locally with its name and key. Returns the conversation id. */
    public synchronized long createGroup(String name) throws ApiException {
        SecretKey key = crypto.generateAesKey();
        Sealed sealed = crypto.encrypt(key, gson.toJson(Map.of("name", name)).getBytes(StandardCharsets.UTF_8), META_AAD);
        String encryptedMeta = sealed.ivBase64() + "." + sealed.ciphertextBase64();

        Dto.Conversation created = api.createGroup(encryptedMeta);

        conversations.upsert(created.conversationId(), ConversationType.GROUP, name, null, null, created.epoch());
        contacts.upsert(me, myUsername, null, null);
        for (long memberId : created.memberIds()) {
            contacts.ensure(memberId);
            conversations.addMember(created.conversationId(), memberId, memberId == me ? "OWNER" : "MEMBER");
        }
        groupKeys.save(created.conversationId(), me, created.epoch(), Base64.getEncoder().encodeToString(key.getEncoded()));
        return created.conversationId();
    }

    /** Owner only. Replaces the previous active code; the raw code is only ever returned here. */
    public Dto.Invite mintInvite(long conversationId) throws ApiException {
        Dto.Invite invite = api.mintInvite(conversationId, DEFAULT_INVITE_MINUTES);
        // The server returns the raw code only this once, so keep it (with its expiry) in the local database.
        conversations.saveInvite(conversationId, invite.code(), java.time.Instant.parse(invite.expiresAt()));
        return invite;
    }

    /**
     * Redeems an invite code and stores the group locally. Returns the conversation id.
     * A malformed code is rejected here, and a refused code leaves nothing behind locally:
     * the group is stored only after the server has accepted the code.
     */
    public synchronized long join(String code) throws ApiException {
        String normalized = normalizeCode(code);
        if (!hasValidShape(normalized)) {
            throw new ApiException(400, INVALID_CODE_MESSAGE);
        }
        Dto.Conversation joined;
        try {
            joined = api.joinByInvite(normalized);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw new ApiException(404, "This invite code does not exist, has expired or was revoked.");
            }
            throw e;
        }
        saveRemote(joined);
        return joined.conversationId();
    }

    /** Stores a conversation (and its member ids) as the server describes it. */
    public synchronized void store(Dto.Conversation conversation) {
        saveRemote(conversation);
    }

    /**
     * Makes the local database match the server: stores every conversation we belong to with its
     * current members, and removes the ones we no longer belong to (we left, were kicked, or the
     * group was deleted while we were offline).
     */
    public synchronized Refresh refreshConversations() throws ApiException {
        Dto.Conversation[] all = api.conversations();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Dto.Conversation conversation : all) {
            saveRemote(conversation);
            ids.add(conversation.conversationId());
        }
        return new Refresh(conversations.deleteAllExcept(ids) > 0, all);
    }

    /** What a refresh found: whether some conversation was removed, and the server's current list. */
    public record Refresh(boolean removedAny, Dto.Conversation[] conversations) {
    }

    /** A member leaves. An owner cannot leave while others remain (the server refuses); see {@link #delete}. */
    public synchronized void leave(long conversationId) throws ApiException {
        api.leaveGroup(conversationId);
        conversations.delete(conversationId);
    }

    /** Owner only: deletes the group for everyone. */
    public synchronized void delete(long conversationId) throws ApiException {
        api.deleteGroup(conversationId);
        conversations.delete(conversationId);
    }

    /** Owner only: removes a member. The server also revokes the active invite code. */
    public synchronized void kick(long conversationId, long userId) throws ApiException {
        api.kickMember(conversationId, userId);
        conversations.removeMember(conversationId, userId);
        conversations.clearInvite(conversationId);
    }

    /**
     * Stores a conversation as the server describes it. Group names and other members' usernames
     * are not known yet (the name is sealed with the owner's key; the server gives only user ids),
     * so those show as "Group #id" and "@user#id" until the key and username arrive.
     */
    private void saveRemote(Dto.Conversation c) {
        boolean direct = "DIRECT".equals(c.type());
        Long low = null;
        Long high = null;
        if (direct && c.memberIds().size() == 2) {
            low = Math.min(c.memberIds().get(0), c.memberIds().get(1));
            high = Math.max(c.memberIds().get(0), c.memberIds().get(1));
        }
        conversations.upsert(c.conversationId(), direct ? ConversationType.DIRECT : ConversationType.GROUP,
                null, low, high, c.epoch());
        contacts.upsert(me, myUsername, null, null);
        for (long memberId : c.memberIds()) {
            contacts.ensure(memberId);
            String role = memberId == me ? (c.myRole() == null ? "MEMBER" : c.myRole()) : "MEMBER";
            conversations.addMember(c.conversationId(), memberId, role);
        }
        conversations.retainMembers(c.conversationId(), c.memberIds()); // drop whoever left or was kicked
    }

    public static final String INVALID_CODE_MESSAGE =
            "Invite codes have 12 characters, for example 7Q4M-2X9T-AB3C.";

    /**
     * The server's normalisation (upper case, no spaces or hyphens) plus the usual look-alike
     * fix: codes never contain O, I or L, so those typed by hand mean 0 and 1.
     */
    public static String normalizeCode(String code) {
        if (code == null) return "";
        return code.replaceAll("[\\s-]", "").toUpperCase().replace('O', '0').replace('I', '1').replace('L', '1');
    }

    /** True for exactly 12 characters from the server's alphabet (digits and letters except I, L, O, U). */
    public static boolean hasValidShape(String normalized) {
        return normalized != null && normalized.matches("[0-9A-HJKMNP-TV-Z]{12}");
    }

    /** Groups of four for reading aloud and typing: 7Q4M-2X9T-AB3C. */
    public static String formatCode(String code) {
        return code.length() == 12 ? code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8) : code;
    }
}
