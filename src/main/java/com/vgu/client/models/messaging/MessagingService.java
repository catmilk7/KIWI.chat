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
                conversation = api.conversation(message.conversationId());
            } catch (ApiException e) {
                if (e.status() == 403 || e.status() == 404) {
                    conversations.delete(message.conversationId()); // we were removed, or it was deleted
                }
                throw e;
            }
            groups.store(conversation);
            if (!"GROUP".equals(conversation.type())) {
                throw new ApiException(400, "Direct messages are not supported yet.");
            }
            long epoch = conversation.epoch();
            SecretKey key = ensureSenderKey(conversation, epoch);

            Envelope envelope = crypto.sealGroup(key, message.type(), message.conversationId(), me, epoch,
                    message.clientMsgId(), message.content());
            Dto.SendResult result = api.sendMessage(message.conversationId(), envelope.ciphertext(), envelope.iv(),
                    message.type().name(), message.clientMsgId(), epoch, null);
            messages.markSent(message.clientMsgId(), result.messageId(), Instant.parse(result.createdAt()));
        } catch (ApiException e) {
            if (!e.isNetworkError()) {
                messages.markFailed(message.clientMsgId());
            }
            throw e;
        } catch (RuntimeException e) {
            messages.markFailed(message.clientMsgId());
            throw new ApiException(500, "Could not send the message: " + e.getMessage());
        }
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
        long id = conversation.conversationId();
        Optional<GroupKeyDao.Entry> entry = groupKeys.findEntry(id, me, epoch);
        if (entry.isEmpty()) {
            groupKeys.save(id, me, epoch, MessageCrypto.keyToBase64(crypto.newSenderKey()), false);
            entry = groupKeys.findEntry(id, me, epoch);
        }
        String keyBase64 = entry.orElseThrow().keyBase64();
        if (!entry.get().distributed()) {
            for (long member : conversation.memberIds()) {
                if (member != me) {
                    sendPackage(id, epoch, member, keyBase64);
                }
            }
            groupKeys.markDistributed(id, me, epoch);
        }
        return MessageCrypto.keyFromBase64(keyBase64);
    }

    private void sendPackage(long conversationId, long epoch, long recipient, String keyBase64) throws ApiException {
        PublicKey recipientKey = KeyCodec.decodePublic(keysOf(recipient).encryptionPublicKey());
        String clientMsgId = UUID.randomUUID().toString();
        Envelope envelope = crypto.sealPackage(recipientKey, conversationId, me, recipient, epoch, clientMsgId,
                new SenderKeyPackage(keyBase64, myUsername, conversations.title(conversationId)));
        api.sendMessage(conversationId, envelope.ciphertext(), envelope.iv(), MessageType.SENDER_KEY.name(),
                clientMsgId, epoch, recipient);
    }

    /** A user's public keys: from our contacts if known, otherwise fetched from the server and remembered. */
    private ContactDao.Keys keysOf(long userId) throws ApiException {
        ContactDao.Keys known = contacts.keys(userId);
        if (known != null) {
            return known;
        }
        Dto.KeyBundle bundle = api.keyBundle(userId);
        contacts.saveKeys(userId, bundle.encryptionPublicKey(), bundle.identitySigningPublicKey());
        return new ContactDao.Keys(bundle.encryptionPublicKey(), bundle.identitySigningPublicKey());
    }

    // -------------------------------------------------------------- receiving

    /**
     * Reacts to a roster change: refreshes the conversation list, then hands our sender key (with our
     * username and the group name) to everyone who does not have it for the current epoch.
     */
    public synchronized void refreshAndAnnounce() throws ApiException {
        announce(groups.refreshConversations().conversations());
    }

    /**
     * Every membership change starts a new epoch, and a new epoch needs new sender keys. Handing ours out
     * straight away (not only when we next type) is what lets a newcomer learn the group name and the
     * members' usernames, and read everyone's first message, without anyone having to write first.
     * It is cheap when nothing changed: the key is already marked as distributed.
     */
    private void announce(Dto.Conversation[] all) throws ApiException {
        for (Dto.Conversation conversation : all) {
            if ("GROUP".equals(conversation.type()) && conversation.memberIds().size() > 1) {
                try {
                    ensureSenderKey(conversation, conversation.epoch());
                } catch (ApiException e) {
                    if (e.isNetworkError()) {
                        throw e;
                    }
                    System.err.println("[msg] could not announce our key in conversation "
                            + conversation.conversationId() + ": " + e.getMessage());
                }
            }
        }
    }

    /** Catches up with everything the server holds after our cursor (see GET /api/sync). */
    public synchronized boolean syncAll() throws ApiException {
        GroupService.Refresh refresh = groups.refreshConversations(); // groups we were removed from disappear here
        boolean changed = refresh.removedAny();
        announce(refresh.conversations());
        while (true) {
            Dto.MessagePage page = api.sync(account.get().syncCursor(), SYNC_PAGE);
            for (Dto.SyncMessage message : page.messages()) {
                handleIncoming(message);
                changed = true;
            }
            if (!page.hasMore() || page.messages().isEmpty()) {
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
        if (!conversations.exists(message.conversationId())) {
            groups.refreshConversations();
            if (!conversations.exists(message.conversationId())) {
                return; // not ours (any more)
            }
        }
        contacts.ensure(message.senderId()); // shown as "@user#id" until the sender's own package names them
        MessageType type = MessageType.valueOf(message.type());
        long epoch = message.epoch() == null ? 0 : message.epoch();
        Envelope envelope = new Envelope(message.ciphertext(), message.iv());

        if (type == MessageType.SENDER_KEY) {
            if (message.recipientId() != null && message.recipientId() == me) {
                acceptSenderKey(message, epoch, envelope);
            }
        } else {
            String plaintext = decrypt(message, type, epoch, envelope);
            messages.saveIncoming(message.messageId(), message.clientMsgId(), message.conversationId(),
                    message.senderId(), type, plaintext, Instant.parse(message.createdAt()));
        }
        account.advanceCursor(message.messageId());
    }

    private void acceptSenderKey(Dto.SyncMessage m, long epoch, Envelope envelope) throws ApiException {
        PublicKey senderSigning = KeyCodec.decodePublic(keysOf(m.senderId()).identitySigningPublicKey());
        try {
            SenderKeyPackage pkg = crypto.openPackage(encryptionPrivateKey, senderSigning, m.conversationId(),
                    m.senderId(), me, epoch, m.clientMsgId(), envelope);
            groupKeys.save(m.conversationId(), m.senderId(), epoch, pkg.key(), true);
            if (pkg.username() != null && !pkg.username().isBlank()) {
                contacts.rename(m.senderId(), pkg.username());
            }
            if (pkg.groupName() != null && !pkg.groupName().isBlank()) {
                conversations.setTitleIfUnknown(m.conversationId(), pkg.groupName());
            }
        } catch (CryptoException | IllegalArgumentException e) {
            System.err.println("[msg] ignored an unreadable sender key from user " + m.senderId() + ": " + e.getMessage());
        }
    }

    private String decrypt(Dto.SyncMessage m, MessageType type, long epoch, Envelope envelope) throws ApiException {
        Optional<String> key = groupKeys.find(m.conversationId(), m.senderId(), epoch);
        if (key.isEmpty()) {
            return UNREADABLE;
        }
        try {
            PublicKey senderSigning = KeyCodec.decodePublic(keysOf(m.senderId()).identitySigningPublicKey());
            return crypto.openGroup(MessageCrypto.keyFromBase64(key.get()), senderSigning, type, m.conversationId(),
                    m.senderId(), epoch, m.clientMsgId(), envelope);
        } catch (CryptoException | IllegalArgumentException e) {
            System.err.println("[msg] could not decrypt message " + m.messageId() + ": " + e.getMessage());
            return UNREADABLE;
        }
    }
}
