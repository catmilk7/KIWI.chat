package com.vgu.client.service;

import com.google.gson.Gson;
import com.vgu.client.enums.ConversationType;
import com.vgu.client.crypto.CryptoService;
import com.vgu.client.crypto.Sealed;
import com.vgu.client.network.ApiException;
import com.vgu.client.network.Dto;
import com.vgu.client.network.ServerApi;
import com.vgu.client.storage.ContactDao;
import com.vgu.client.storage.ConversationDao;
import com.vgu.client.storage.GroupKeyDao;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Group conversations: the server stores only an opaque {@code encryptedMeta}, so the group name is
 * sealed with a <em>meta key</em> that every member holds (it travels in the sender key packages) and the
 * server never sees the name. Because the server lets only the owner replace the metadata, a rename
 * is authoritative: members just read the new sealed name the next time they refresh, even if the
 * owner is offline by then. Invite codes are minted and redeemed on the server.
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
        SecretKey metaKey = crypto.generateAesKey();
        String encryptedMeta = sealName(metaKey, name);

        Dto.Conversation created = api.createGroup(encryptedMeta);

        conversations.upsert(created.getConversationId(), ConversationType.GROUP, name, null, null, created.getEpoch());
        contacts.upsert(me, myUsername, null, null);
        for (long memberId : created.getMemberIds()) {
            contacts.ensure(memberId);
            conversations.addMember(created.getConversationId(), memberId, memberId == me ? "OWNER" : "MEMBER");
        }
        groupKeys.saveMeta(created.getConversationId(), Base64.getEncoder().encodeToString(metaKey.getEncoded()));
        conversations.setEncryptedMeta(created.getConversationId(), encryptedMeta);
        return created.getConversationId();
    }

    /** Longest group name the client accepts. */
    public static final int MAX_NAME_LENGTH = 100;

    /**
     * Owner only: renames the group. The new name is sealed with the group's meta key and sent to the
     * server, which refuses anyone but the owner; members pick it up on their next refresh.
     */
    public synchronized void rename(long conversationId, String newName) throws ApiException {
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new ApiException(400, "A group name has 1-" + MAX_NAME_LENGTH + " characters.");
        }
        if (!"OWNER".equals(conversations.myRole(conversationId))) {
            throw new ApiException(403, "Only the group owner can rename the group.");
        }
        Optional<String> known = groupKeys.findMeta(conversationId);
        SecretKey metaKey = known.isPresent() ? decodeKey(known.get()) : crypto.generateAesKey();
        String encryptedMeta = sealName(metaKey, name);

        api.replaceGroupMeta(conversationId, encryptedMeta);

        if (known.isEmpty()) {
            // A group made before names had a shared key: members do not have one yet. Hand ours out again.
            groupKeys.saveMeta(conversationId, Base64.getEncoder().encodeToString(metaKey.getEncoded()));
            groupKeys.markUndistributed(conversationId, me);
        }
        conversations.setEncryptedMeta(conversationId, encryptedMeta);
        conversations.setTitle(conversationId, name);
    }

    /**
     * A meta key arrived (in a sender key package). If it opens this group's sealed name it becomes the group's
     * meta key and the title is updated; a key that does not open it is ignored.
     */
    public synchronized void acceptMetaKey(long conversationId, String keyBase64) {
        String meta = conversations.encryptedMeta(conversationId);
        if (meta != null && openName(decodeKey(keyBase64), meta) == null) {
            return;
        }
        groupKeys.saveMeta(conversationId, keyBase64);
        applyMeta(conversationId);
    }

    /** Sets the title from the sealed name on the server, if we hold the key that opens it. */
    private void applyMeta(long conversationId) {
        String meta = conversations.encryptedMeta(conversationId);
        Optional<String> key = groupKeys.findMeta(conversationId);
        if (meta == null || key.isEmpty()) {
            return;
        }
        String name = openName(decodeKey(key.get()), meta);
        if (name != null && !name.isBlank()) {
            conversations.setTitle(conversationId, name);
        }
    }

    private String sealName(SecretKey key, String name) {
        Sealed sealed = crypto.encrypt(key, gson.toJson(Map.of("name", name)).getBytes(StandardCharsets.UTF_8), META_AAD);
        return sealed.ivBase64() + "." + sealed.ciphertextBase64();
    }

    /** The name inside {@code encryptedMeta}, or null if the key does not open it or it is not a valid name. */
    private String openName(SecretKey key, String encryptedMeta) {
        try {
            String[] parts = encryptedMeta.split("\\.", -1);
            if (parts.length != 2) {
                return null;
            }
            byte[] json = crypto.decrypt(key, new Sealed(Base64.getDecoder().decode(parts[0]),
                    Base64.getDecoder().decode(parts[1])), META_AAD);
            Map<?, ?> content = gson.fromJson(new String(json, StandardCharsets.UTF_8), Map.class);
            return content == null ? null : (String) content.get("name");
        } catch (RuntimeException e) { // wrong key, tampering, or not our format (older groups)
            return null;
        }
    }

    private static SecretKey decodeKey(String base64) {
        return new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(base64), "AES");
    }

    /** Owner only. Replaces the previous active code; the raw code is only ever returned here. */
    public Dto.Invite mintInvite(long conversationId) throws ApiException {
        Dto.Invite invite = api.mintInvite(conversationId, DEFAULT_INVITE_MINUTES);
        // The server returns the raw code only this once, so keep it (with its expiry) in the local database.
        conversations.saveInvite(conversationId, invite.getCode(), java.time.Instant.parse(invite.getExpiresAt()));
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
        return joined.getConversationId();
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
            ids.add(conversation.getConversationId());
        }
        return new Refresh(conversations.deleteAllExcept(ids) > 0, all);
    }

    /** What a refresh found: whether some conversation was removed, and the server's current list. */
    public static final class Refresh {
        private final boolean removedAny;
        private final Dto.Conversation[] conversations;

        public Refresh(boolean removedAny, Dto.Conversation[] conversations) {
            this.removedAny = removedAny;
            this.conversations = conversations;
        }

        public boolean isRemovedAny() {
            return removedAny;
        }

        public Dto.Conversation[] getConversations() {
            return conversations;
        }
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
        boolean direct = "DIRECT".equals(c.getType());
        Long low = null;
        Long high = null;
        if (direct && c.getMemberIds().size() == 2) {
            low = Math.min(c.getMemberIds().get(0), c.getMemberIds().get(1));
            high = Math.max(c.getMemberIds().get(0), c.getMemberIds().get(1));
        }
        conversations.upsert(c.getConversationId(), direct ? ConversationType.DIRECT : ConversationType.GROUP,
                null, low, high, c.getEpoch());
        if (!direct && c.getEncryptedMeta() != null) {
            conversations.setEncryptedMeta(c.getConversationId(), c.getEncryptedMeta());
            applyMeta(c.getConversationId()); // a rename shows up here
        }
        contacts.upsert(me, myUsername, null, null);
        for (long memberId : c.getMemberIds()) {
            contacts.ensure(memberId);
            String role = memberId == me ? (c.getMyRole() == null ? "MEMBER" : c.getMyRole()) : "MEMBER";
            conversations.addMember(c.getConversationId(), memberId, role);
        }
        conversations.retainMembers(c.getConversationId(), c.getMemberIds()); // drop whoever left or was kicked
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
