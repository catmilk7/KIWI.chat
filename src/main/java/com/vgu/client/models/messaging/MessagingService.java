package com.vgu.client.models.messaging;

import com.vgu.client.enums.MessageType;
import com.vgu.client.models.crypto.CryptoException;
import com.vgu.client.models.crypto.KeyCodec;
import com.vgu.client.models.messaging.MessageCrypto.Envelope;
import com.vgu.client.models.messaging.MessageCrypto.SenderKeyPackage;
import com.vgu.client.models.network.ApiException;
import com.vgu.client.models.network.Dto;
import com.vgu.client.models.network.ServerApi;
import com.vgu.client.models.storage.AccountDao;
import com.vgu.client.models.storage.ContactDao;
import com.vgu.client.models.storage.ConversationDao;
import com.vgu.client.models.storage.GroupKeyDao;
import com.vgu.client.models.storage.MessageDao;

import javax.crypto.SecretKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The chat protocol over the server: sends messages (distributing our sender key to the group
 * first when needed), and turns everything arriving by push or sync into local rows. Calls are
 * serialised ({@code synchronized}); run them off the JavaFX thread.
 */
public final class MessagingService {

    static final String UNREADABLE = "🔒 This message could not be decrypted.";
    private static final int SYNC_PAGE = 200;

    private final ServerApi api;
    private final MessageCrypto crypto;
    private final GroupService groups;
    private final AccountDao account;
    private final ConversationDao conversations;
    private final MessageDao messages;
    private final ContactDao contacts;
    private final GroupKeyDao groupKeys;
    private final java.security.PrivateKey encryptionPrivateKey;
    private final long me;
    private final String myUsername;

    public MessagingService(ServerApi api, MessageCrypto crypto, GroupService groups, AccountDao account,
                            ConversationDao conversations, MessageDao messages, ContactDao contacts,
                            GroupKeyDao groupKeys, java.security.PrivateKey encryptionPrivateKey, long me,
                            String myUsername) {
        this.api = api;
        this.crypto = crypto;
        this.groups = groups;
        this.account = account;
        this.conversations = conversations;
        this.messages = messages;
        this.contacts = contacts;
        this.groupKeys = groupKeys;
        this.encryptionPrivateKey = encryptionPrivateKey;
        this.me = me;
        this.myUsername = myUsername;
    }

    // ---------------------------------------------------------------- sending

    /**
     * Sends a message that is already stored locally as SENDING. On a network failure it stays
     * SENDING (it is retried later); on any other failure it becomes FAILED.
     */
    public synchronized void transmit(Message message) throws ApiException {
        try {
            Dto.Conversation conversation;
            try {
                conversation = api.conversation(message.getConversationId());
            } catch (ApiException e) {
                if (e.status() == 403 || e.status() == 404) {
                    conversations.delete(message.getConversationId()); // we were removed, or it was deleted
                }
                throw e;
            }
            groups.store(conversation);
            if ("DIRECT".equals(conversation.getType())) {
                transmitDirect(message, conversation);
                return;
            }
            long epoch = conversation.getEpoch();
            SecretKey key = ensureSenderKey(conversation, epoch);

            Envelope envelope = crypto.sealGroup(key, message.getType(), message.getConversationId(), me, epoch,
                    message.getClientMsgId(), message.getContent());
            Dto.SendResult result = api.sendMessage(message.getConversationId(), envelope.getCiphertext(), envelope.getIv(),
                    message.getType().name(), message.getClientMsgId(), epoch, null);
            messages.markSent(message.getClientMsgId(), result.getMessageId(), Instant.parse(result.getCreatedAt()));
        } catch (ApiException e) {
            if (!e.isNetworkError()) {
                messages.markFailed(message.getClientMsgId());
            }
            throw e;
        } catch (RuntimeException e) {
            messages.markFailed(message.getClientMsgId());
            throw new ApiException(500, "Could not send the message: " + e.getMessage());
        }
    }

    /** A direct message: sealed for the peer (and for us), no epoch and no sender key. */
    private void transmitDirect(Message message, Dto.Conversation conversation) throws ApiException {
        long peer = peerOf(conversation);
        PublicKey peerKey = KeyCodec.decodePublic(keysOf(peer).getEncryptionPublicKey());
        Envelope envelope = crypto.sealDirect(peerKey, message.getType(), message.getConversationId(), me,
                message.getClientMsgId(), myUsername, message.getContent());
        Dto.SendResult result = api.sendMessage(message.getConversationId(), envelope.getCiphertext(), envelope.getIv(),
                message.getType().name(), message.getClientMsgId(), null, null);
        messages.markSent(message.getClientMsgId(), result.getMessageId(), Instant.parse(result.getCreatedAt()));
    }

    private long peerOf(Dto.Conversation conversation) throws ApiException {
        for (long member : conversation.getMemberIds()) {
            if (member != me) {
                return member;
            }
        }
        throw new ApiException(400, "This direct conversation has no other member.");
    }

    /**
     * Opens (or reopens) the direct chat with another user and stores it locally. {@code knownUsername}
     * is what the person is called where we met them (for example in a group); it is only a display name
     * until their first message names them. Returns the conversation id.
     */
    public synchronized long startDirect(long peerId, String knownUsername) throws ApiException {
        if (peerId == me) {
            throw new ApiException(400, "You cannot start a chat with yourself.");
        }
        Dto.Conversation conversation = api.createDirect(peerId);
        keysOf(peerId); // learn the peer's public keys now, so sending later needs no extra request
        groups.store(conversation);
        String name = knownUsername == null ? "" : knownUsername.startsWith("@") ? knownUsername.substring(1) : knownUsername;
        if (!name.isBlank() && !name.startsWith("user#")) { // "user#id" is only a placeholder, not a real name
            contacts.rename(peerId, name);
        }
        return conversation.getConversationId();
    }

    /** Sends everything still waiting (SENDING or FAILED), oldest first. Stops at the first network error. */
    public synchronized void retryPending() throws ApiException {
        for (Message pending : messages.pendingOutgoing()) {
            try {
                transmit(pending);
            } catch (ApiException e) {
                if (e.isNetworkError()) {
                    throw e;
                }
                // a permanent failure of one message must not block the others
            }
        }
    }

    /** Our sender key for this epoch; created and sent to every member the first time it is needed. */
    private SecretKey ensureSenderKey(Dto.Conversation conversation, long epoch) throws ApiException {
        long id = conversation.getConversationId();
        Optional<GroupKeyDao.Entry> entry = groupKeys.findEntry(id, me, epoch);
        if (entry.isEmpty()) {
            groupKeys.save(id, me, epoch, MessageCrypto.keyToBase64(crypto.newSenderKey()), false);
            entry = groupKeys.findEntry(id, me, epoch);
        }
        String keyBase64 = entry.orElseThrow().getKeyBase64();
        if (!entry.get().isDistributed()) {
            for (long member : conversation.getMemberIds()) {
                if (member != me) {
                    sendPackage(id, epoch, member, keyBase64);
                }
            }
            groupKeys.markDistributed(id, me, epoch);
        }
        return MessageCrypto.keyFromBase64(keyBase64);
    }

    private void sendPackage(long conversationId, long epoch, long recipient, String keyBase64) throws ApiException {
        PublicKey recipientKey = KeyCodec.decodePublic(keysOf(recipient).getEncryptionPublicKey());
        String clientMsgId = UUID.randomUUID().toString();
        Envelope envelope = crypto.sealPackage(recipientKey, conversationId, me, recipient, epoch, clientMsgId,
                new SenderKeyPackage(keyBase64, myUsername, conversations.title(conversationId)));
        api.sendMessage(conversationId, envelope.getCiphertext(), envelope.getIv(), MessageType.SENDER_KEY.name(),
                clientMsgId, epoch, recipient);
    }

    /** A user's public keys: from our contacts if known, otherwise fetched from the server and remembered. */
    private ContactDao.Keys keysOf(long userId) throws ApiException {
        ContactDao.Keys known = contacts.keys(userId);
        if (known != null) {
            return known;
        }
        Dto.KeyBundle bundle = api.keyBundle(userId);
        contacts.saveKeys(userId, bundle.getEncryptionPublicKey(), bundle.getIdentitySigningPublicKey());
        return new ContactDao.Keys(bundle.getEncryptionPublicKey(), bundle.getIdentitySigningPublicKey());
    }

    // -------------------------------------------------------------- receiving

    /**
     * Reacts to a roster change: refreshes the conversation list, then hands our sender key (with our
     * username and the group name) to everyone who does not have it for the current epoch.
     */
    public synchronized void refreshAndAnnounce() throws ApiException {
        announce(groups.refreshConversations().getConversations());
    }

    /**
     * Every membership change starts a new epoch, and a new epoch needs new sender keys. Handing ours out
     * straight away (not only when we next type) is what lets a newcomer learn the group name and the
     * members' usernames, and read everyone's first message, without anyone having to write first.
     * It is cheap when nothing changed: the key is already marked as distributed.
     */
    private void announce(Dto.Conversation[] all) throws ApiException {
        for (Dto.Conversation conversation : all) {
            if ("GROUP".equals(conversation.getType()) && conversation.getMemberIds().size() > 1) {
                try {
                    ensureSenderKey(conversation, conversation.getEpoch());
                } catch (ApiException e) {
                    if (e.isNetworkError()) {
                        throw e;
                    }
                    System.err.println("[msg] could not announce our key in conversation "
                            + conversation.getConversationId() + ": " + e.getMessage());
                }
            }
        }
    }

    /** Catches up with everything the server holds after our cursor (see GET /api/sync). */
    public synchronized boolean syncAll() throws ApiException {
        GroupService.Refresh refresh = groups.refreshConversations(); // groups we were removed from disappear here
        boolean changed = refresh.isRemovedAny();
        announce(refresh.getConversations());
        while (true) {
            Dto.MessagePage page = api.sync(account.get().getSyncCursor(), SYNC_PAGE);
            for (Dto.SyncMessage message : page.getMessages()) {
                handleIncoming(message);
                changed = true;
            }
            if (!page.hasMore() || page.getMessages().isEmpty()) {
                return changed;
            }
        }
    }

    /**
     * Applies one message from the server. A message we cannot read becomes a placeholder row
     * instead of an exception, so one bad message never blocks the rest. Network errors (fetching
     * keys or conversations) propagate so the cursor stays put and the message is retried.
     */
    public synchronized void handleIncoming(Dto.SyncMessage message) throws ApiException {
        if (!conversations.exists(message.getConversationId())) {
            groups.refreshConversations();
            if (!conversations.exists(message.getConversationId())) {
                return; // not ours (any more)
            }
        }
        contacts.ensure(message.getSenderId()); // shown as "@user#id" until the sender's own package names them
        MessageType type = MessageType.valueOf(message.getType());
        long epoch = message.getEpoch() == null ? 0 : message.getEpoch();
        Envelope envelope = new Envelope(message.getCiphertext(), message.getIv());

        if (type == MessageType.SENDER_KEY) {
            if (message.getRecipientId() != null && message.getRecipientId() == me) {
                acceptSenderKey(message, epoch, envelope);
            }
        } else if (conversations.isDirect(message.getConversationId())) {
            String plaintext = decryptDirect(message, type, envelope);
            messages.saveIncoming(message.getMessageId(), message.getClientMsgId(), message.getConversationId(),
                    message.getSenderId(), type, plaintext, Instant.parse(message.getCreatedAt()));
        } else {
            String plaintext = decrypt(message, type, epoch, envelope);
            messages.saveIncoming(message.getMessageId(), message.getClientMsgId(), message.getConversationId(),
                    message.getSenderId(), type, plaintext, Instant.parse(message.getCreatedAt()));
        }
        account.advanceCursor(message.getMessageId());
    }

    private void acceptSenderKey(Dto.SyncMessage m, long epoch, Envelope envelope) throws ApiException {
        PublicKey senderSigning = KeyCodec.decodePublic(keysOf(m.getSenderId()).getIdentitySigningPublicKey());
        try {
            SenderKeyPackage pkg = crypto.openPackage(encryptionPrivateKey, senderSigning, m.getConversationId(),
                    m.getSenderId(), me, epoch, m.getClientMsgId(), envelope);
            groupKeys.save(m.getConversationId(), m.getSenderId(), epoch, pkg.getKey(), true);
            if (pkg.getUsername() != null && !pkg.getUsername().isBlank()) {
                contacts.rename(m.getSenderId(), pkg.getUsername());
            }
            if (pkg.getGroupName() != null && !pkg.getGroupName().isBlank()) {
                conversations.setTitleIfUnknown(m.getConversationId(), pkg.getGroupName());
            }
        } catch (CryptoException | IllegalArgumentException e) {
            System.err.println("[msg] ignored an unreadable sender key from user " + m.getSenderId() + ": " + e.getMessage());
        }
    }

    private String decryptDirect(Dto.SyncMessage m, MessageType type, Envelope envelope) throws ApiException {
        try {
            PublicKey senderSigning = KeyCodec.decodePublic(keysOf(m.getSenderId()).getIdentitySigningPublicKey());
            MessageCrypto.DirectPayload payload = crypto.openDirect(encryptionPrivateKey, senderSigning,
                    m.getSenderId() == me, type, m.getConversationId(), m.getSenderId(), m.getClientMsgId(),
                    new Envelope(m.getCiphertext(), m.getIv()));
            if (m.getSenderId() != me && payload.getSender() != null && !payload.getSender().isBlank()) {
                contacts.rename(m.getSenderId(), payload.getSender()); // signed and sealed by the sender, so it can be trusted as much as the key
            }
            return payload.getBody();
        } catch (CryptoException | IllegalArgumentException e) {
            System.err.println("[msg] could not decrypt direct message " + m.getMessageId() + ": " + e.getMessage());
            return UNREADABLE;
        }
    }

    private String decrypt(Dto.SyncMessage m, MessageType type, long epoch, Envelope envelope) throws ApiException {
        Optional<String> key = groupKeys.find(m.getConversationId(), m.getSenderId(), epoch);
        if (key.isEmpty()) {
            return UNREADABLE;
        }
        try {
            PublicKey senderSigning = KeyCodec.decodePublic(keysOf(m.getSenderId()).getIdentitySigningPublicKey());
            return crypto.openGroup(MessageCrypto.keyFromBase64(key.get()), senderSigning, type, m.getConversationId(),
                    m.getSenderId(), epoch, m.getClientMsgId(), envelope);
        } catch (CryptoException | IllegalArgumentException e) {
            System.err.println("[msg] could not decrypt message " + m.getMessageId() + ": " + e.getMessage());
            return UNREADABLE;
        }
    }
}
