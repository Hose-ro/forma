package com.olvenio.identity.application;

import com.olvenio.identity.application.IdentityDtos.RegisterRequest;
import com.olvenio.identity.application.IdentityDtos.UserView;
import com.olvenio.identity.domain.User;
import com.olvenio.shared.domain.BusinessRuleException;
import com.olvenio.shared.domain.RateLimitException;
import com.olvenio.shared.security.RequestLimits;
import com.olvenio.shared.security.SecretTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailRegistrationService {
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final AccountService accounts;
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final RequestLimits limits;
    private final SecretTokens tokens;
    private final TransactionTemplate tx;

    public EmailRegistrationService(AccountService accounts, JdbcTemplate db, PasswordEncoder encoder,
                                    RequestLimits limits, SecretTokens tokens, TransactionTemplate tx) {
        this.accounts = accounts;
        this.db = db;
        this.encoder = encoder;
        this.limits = limits;
        this.tokens = tokens;
        this.tx = tx;
    }

    @Transactional
    public UUID begin(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());
        accounts.requireAvailableEmail(email);
        limitMail(email);
        UUID id = UUID.randomUUID();
        db.update("""
                insert into account_emails(id,email,purpose,expires_at)
                values (?,?,'REGISTRATION_CODE',now()+interval '15 minutes')
                """, id, email);
        db.update("""
                insert into pending_registrations(id,password_hash,first_name,last_name,phone,terms_accepted_at)
                values (?,?,?,?,?,now())
                """, id, encoder.encode(request.password()), request.firstName(), request.lastName(), request.phone());
        return id;
    }

    @Transactional
    public void resend(UUID id) {
        Pending pending = requirePending(id);
        int queued = db.update("""
                update account_emails set status='PENDING',attempts=0,next_attempt_at=now(),last_error=null
                where id=? and status in ('SENT','FAILED')
                    and coalesce(sent_at,created_at)<=now()-interval '1 minute'
                """, id);
        if (queued == 0) throw new RateLimitException();
        limitMail(pending.email());
    }

    public UserView verify(UUID id, String code) {
        UserView user = tx.execute(status -> {
            Pending pending = requirePending(id);
            if (!MessageDigest.isEqual(tokens.registrationCode(id).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                db.update("update pending_registrations set failed_attempts=failed_attempts+1 where id=?", id);
                return null;
            }
            UserView created = accounts.registerVerified(pending.email(), pending.passwordHash(), pending.firstName(),
                    pending.lastName(), pending.phone(), pending.termsAcceptedAt());
            db.update("delete from pending_registrations where id=?", id);
            return created;
        });
        // Reject outside the transaction so an incorrect guess still spends an attempt.
        if (user == null) throw new BusinessRuleException("Incorrect registration code");
        return user;
    }

    private void limitMail(String email) {
        if (limits.retryAfter("registration:email:" + tokens.hash(email), 3, Duration.ofMinutes(15)) > 0) {
            throw new RateLimitException();
        }
    }

    private Pending requirePending(UUID id) {
        var rows = db.query("""
                select m.email,p.password_hash,p.first_name,p.last_name,p.phone,p.terms_accepted_at
                from pending_registrations p join account_emails m on m.id=p.id
                where p.id=? and m.expires_at>clock_timestamp() and p.failed_attempts<?
                for update of p
                """, (rs, row) -> new Pending(rs.getString("email"), rs.getString("password_hash"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"),
                rs.getTimestamp("terms_accepted_at").toInstant()), id, MAX_CODE_ATTEMPTS);
        if (rows.isEmpty()) throw new BusinessRuleException("Registration code expired or exhausted");
        return rows.getFirst();
    }

    private record Pending(String email, String passwordHash, String firstName, String lastName, String phone,
                           Instant termsAcceptedAt) {}
}
