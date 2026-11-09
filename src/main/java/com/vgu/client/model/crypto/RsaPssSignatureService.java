package com.vgu.client.model.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

/** RSA-PSS with SHA-256, MGF1-SHA-256 and a 32-byte salt. */
public final class RsaPssSignatureService implements SignatureService {

    private static final PSSParameterSpec PSS =
            new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);

    @Override
    public KeyPair generateSigningKeyPair() {
        return KeyCodec.generateRsaKeyPair();
    }

    @Override
    public byte[] sign(PrivateKey identityKey, byte[] data) {
        try {
            Signature sig = Signature.getInstance("RSASSA-PSS");
            sig.setParameter(PSS);
            sig.initSign(identityKey);
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Signing failed", e);
        }
    }

    @Override
    public boolean verify(PublicKey identityKey, byte[] data, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("RSASSA-PSS");
            sig.setParameter(PSS);
            sig.initVerify(identityKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
