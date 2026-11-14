package com.vgu.client.models.network;

import com.google.gson.JsonObject;

/**
 * Something the server pushed over the WebSocket. Each kind of event knows how to hand itself to
 * a {@link Sink}, so the code that receives events never has to look at the kind: it just calls
 * {@link #deliverTo(Sink)}.
 */
public abstract class ServerEvent {

    /** The reactions available to an event; the session decides what each one does. */
    public interface Sink {
        void messageArrived(Dto.SyncMessage message);

        void conversationsChanged();

        void conversationRemoved(long conversationId);
    }

    public abstract void deliverTo(Sink sink);

    /** Builds the event for a server envelope, or returns null for envelopes that need no action (PONG, ACK). */
    public static ServerEvent parse(JsonObject envelope) {
        switch (envelope.get("type").getAsString()) {
            case "MESSAGE":
                return new NewMessage(new Dto.SyncMessage(
                        envelope.get("messageId").getAsLong(), envelope.get("sequence").getAsLong(),
                        envelope.get("conversationId").getAsLong(), envelope.get("senderId").getAsLong(),
                        envelope.get("messageType").getAsString(),
                        envelope.has("recipientId") ? envelope.get("recipientId").getAsLong() : null,
                        envelope.has("epoch") ? envelope.get("epoch").getAsLong() : null,
                        envelope.get("ciphertext").getAsString(), envelope.get("iv").getAsString(),
                        envelope.get("clientMsgId").getAsString(), envelope.get("createdAt").getAsString()));
            case "CONVERSATION_CHANGED":
                return new ConversationChanged();
            case "CONVERSATION_REMOVED":
            case "CONVERSATION_DELETED":
                return new ConversationRemoved(envelope.get("conversationId").getAsLong());
            default:
                return null;
        }
    }

    /** A message was stored for us. */
    public static final class NewMessage extends ServerEvent {
        private final Dto.SyncMessage message;

        NewMessage(Dto.SyncMessage message) {
            this.message = message;
        }

        public Dto.SyncMessage message() {
            return message;
        }

        @Override
        public void deliverTo(Sink sink) {
            sink.messageArrived(message);
        }
    }

    /** A roster or title changed in some conversation we belong to. */
    public static final class ConversationChanged extends ServerEvent {
        @Override
        public void deliverTo(Sink sink) {
            sink.conversationsChanged();
        }
    }

    /** We left, were kicked from, or the owner deleted a conversation. */
    public static final class ConversationRemoved extends ServerEvent {
        private final long conversationId;

        ConversationRemoved(long conversationId) {
            this.conversationId = conversationId;
        }

        public long conversationId() {
            return conversationId;
        }

        @Override
        public void deliverTo(Sink sink) {
            sink.conversationRemoved(conversationId);
        }
    }
}
