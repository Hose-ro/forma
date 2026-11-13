package com.velora.payments.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSignature {
    private WebhookSignature() {}
    // Official SDK preserves data.id exactly as received in the URL, never from the body.
    public static boolean valid(String signature,String requestId,String dataId,String secret) {
        if (signature==null || signature.length()>512 || requestId==null || !requestId.matches("[A-Za-z0-9_-]{1,200}")
                || dataId==null || !dataId.matches("[A-Za-z0-9]{1,100}") || secret==null || secret.isBlank()) return false;
        try {
            Map<String,String> parts=new HashMap<>();
            for (String entry:signature.split(",")) {
                String[] pair=entry.trim().split("=",2);
                if (pair.length!=2 || parts.putIfAbsent(pair[0],pair[1])!=null) return false;
            }
            String ts=parts.get("ts"), v1=parts.get("v1");
            if (ts==null || !ts.matches("[0-9]{1,16}") || v1==null || !v1.matches("[a-fA-F0-9]{64}")) return false;
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String manifest="id:"+dataId+";request-id:"+requestId+";ts:"+ts+";";
            return MessageDigest.isEqual(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)),HexFormat.of().parseHex(v1));
        } catch (Exception e) { return false; }
    }
}
