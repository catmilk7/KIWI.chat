package com.vgu.client.models.crypto;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/** Identity signatures for message envelopes and sender-key packages. */
public interface SignatureService {

    KeyPair generateSigningKeyPair();

    byte[] sign(PrivateKey identityKey, byte[] data);

    /** Returns false for a bad signature; never throws for invalid input data. */
    boolean verify(PublicKey identityKey, byte[] data, byte[] signature);
}
