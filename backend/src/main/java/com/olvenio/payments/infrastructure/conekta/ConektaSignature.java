package com.olvenio.payments.infrastructure.conekta;

import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/** DIGEST header: base64 RSA signature (SHA-256) of the raw request body, made with Conekta's webhook key. */
public final class ConektaSignature {
    private ConektaSignature() {}
    public static boolean valid(String digest, byte[] body, PublicKey key) {
        if (digest == null || digest.length() > 4096 || body == null || key == null) return false;
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(body);
            return verifier.verify(Base64.getDecoder().decode(digest.trim()));
        } catch (Exception e) { return false; }
    }
}
