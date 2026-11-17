package com.olvenio.payments.infrastructure.conekta;

import com.olvenio.shared.domain.ConflictException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Conekta private API key (sandbox or production key) and the webhook public key (PEM) that verifies the DIGEST
 * header. {@code methods}: hosted-checkout methods to offer; by default only SPEI transfers, since cards and OXXO
 * are usually taken by another provider.
 */
@ConfigurationProperties("app.payments.conekta")
public record ConektaProperties(String privateKey, String webhookPublicKey, String environment, List<String> methods) {
    static final List<String> SUPPORTED = List.of("bank_transfer", "cash", "card");
    public boolean enabled() { return privateKey != null && !privateKey.isBlank(); }
    public boolean production() { return "production".equals(environment); }
    public List<String> allowedMethods() { return methods == null || methods.isEmpty() ? List.of("bank_transfer") : methods; }
    public void requireConfigured() {
        if (!("test".equals(environment) || production()) || !enabled() || !privateKey.matches("key_[A-Za-z0-9]{10,250}")
                || !SUPPORTED.containsAll(allowedMethods()) || publicKey() == null)
            throw new ConflictException("Conekta configuration is incomplete");
    }
    public PublicKey publicKey() {
        if (webhookPublicKey == null || webhookPublicKey.isBlank()) return null;
        try {
            String der = webhookPublicKey.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\\\n|\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(der)));
        } catch (Exception e) { return null; }
    }
}
