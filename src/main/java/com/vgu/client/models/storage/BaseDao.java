package com.vgu.client.models.storage;

import java.time.Instant;

/**
 * What every data-access class has in common: the encrypted database it works on and the way
 * times are written into it (ISO-8601 UTC text). Each DAO extends this and adds the SQL of one
 * part of the chat.
 */
public abstract class BaseDao {

    protected final ChatDatabase db;

    protected BaseDao(ChatDatabase db) {
        this.db = db;
    }

    /** The current time as stored in the database. */
    protected static String now() {
        return Instant.now().toString();
    }
}
