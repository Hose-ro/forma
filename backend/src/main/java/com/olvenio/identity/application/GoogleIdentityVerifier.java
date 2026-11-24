package com.olvenio.identity.application;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.olvenio.identity.domain.User;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GoogleIdentityVerifier {
    private final String clientId;
    private final GoogleIdTokenVerifier verifier;

    public GoogleIdentityVerifier(@Value("${APP_GOOGLE_CLIENT_ID:}") String clientId) {
        this.clientId = clientId.trim();
        this.verifier = this.clientId.isEmpty() ? null : new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(List.of(this.clientId))
                .build();
    }

    public String clientId() {
        if (verifier == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return clientId;
    }

    public GoogleIdentity verify(String credential, String nonce) {
        clientId();
        GoogleIdToken token;
        try {
            token = GoogleIdToken.parse(GsonFactory.getDefaultInstance(), credential);
        } catch (IllegalArgumentException | IOException ex) {
            throw new BadCredentialsException("Invalid Google credential");
        }
        try {
            if (!verifier.verify(token)) throw new BadCredentialsException("Invalid Google credential");
        } catch (GeneralSecurityException | IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google verification unavailable");
        }

        var claims = token.getPayload();
        String email = User.normalizeEmail(claims.getEmail());
        String subject = claims.getSubject();
        if (!nonce.equals(claims.get("nonce")) || !Boolean.TRUE.equals(claims.getEmailVerified())
                || email == null || email.isBlank() || email.length() > 320
                || subject == null || subject.isBlank() || subject.length() > 255) {
            throw new BadCredentialsException("Invalid Google credential");
        }
        boolean googleManagesEmail = email.endsWith("@gmail.com")
                || (claims.getHostedDomain() != null && !claims.getHostedDomain().isBlank());
        return new GoogleIdentity(subject, email, name(claims.get("given_name")), name(claims.get("family_name")),
                googleManagesEmail);
    }

    private static String name(Object value) {
        return value instanceof String name && name.length() <= 100 ? name : "";
    }

    public record GoogleIdentity(String subject, String email, String firstName, String lastName,
                                 boolean googleManagesEmail) {}
}
