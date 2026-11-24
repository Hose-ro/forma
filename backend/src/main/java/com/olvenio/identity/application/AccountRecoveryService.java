package com.olvenio.identity.application;

import com.olvenio.identity.domain.User;
import com.olvenio.identity.infrastructure.UserRepository;
import com.olvenio.shared.domain.BusinessRuleException;
import com.olvenio.shared.security.RequestLimits;
import com.olvenio.shared.security.SecretTokens;
import com.olvenio.shared.security.SessionRevocations;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Password recovery and email verification with single-use, expiring links. Only the HMAC of a token is stored.
 * Requests enqueue durable work before returning the same answer for known and unknown addresses.
 * SMTP retries reconstruct the same link using a domain-separated server HMAC, never a stored bearer token.
 */
@Service
public class AccountRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AccountRecoveryService.class);
    private static final Duration RESET_TTL = Duration.ofMinutes(30);
    private static final Duration VERIFY_TTL = Duration.ofHours(48);
    private static final int MAX_ATTEMPTS = 8;

    private final UserRepository users;
    private final JdbcTemplate db;
    private final SecretTokens tokens;
    private final PasswordEncoder encoder;
    private final SessionRevocations revocations;
    private final RequestLimits limits;
    private final TransactionTemplate tx;
    private final JavaMailSender mail;
    private final String from;
    private final String store;
    private final String frontendUrl;

    public AccountRecoveryService(UserRepository users, JdbcTemplate db, SecretTokens tokens, PasswordEncoder encoder,
                                  SessionRevocations revocations, RequestLimits limits, TransactionTemplate tx,
                                  ObjectProvider<JavaMailSender> mail, @Value("${app.mail.from:}") String from,
                                  @Value("${app.store-name:Olvenio}") String store,
                                  @Value("${app.payments.frontend-url:}") String frontendUrl) {
        this.users = users;
        this.db = db;
        this.tokens = tokens;
        this.encoder = encoder;
        this.revocations = revocations;
        this.limits = limits;
        this.tx = tx;
        this.mail = mail.getIfAvailable();
        this.from = from;
        this.store = store;
        this.frontendUrl = frontendUrl.replaceAll("/$", "");
    }

    /** Both known and unknown addresses are queued; account lookup happens only in the delivery worker. */
    public void requestPasswordReset(String email) {
        enqueue(User.normalizeEmail(email), "PASSWORD_RESET", RESET_TTL);
    }

    public boolean verificationAvailable() {
        return mail != null && !from.isBlank() && !frontendUrl.isBlank();
    }

    public boolean requestVerification(UUID userId) {
        return users.findById(userId).filter(u -> u.isEnabled() && !u.isEmailVerified())
                .map(u -> enqueue(u.getEmail(), "EMAIL_VERIFICATION", VERIFY_TTL)).orElse(true);
    }

    public void resetPassword(String token, String newPassword) {
        UUID userId = tx.execute(t -> {
            User u = consume(token, "PASSWORD_RESET");
            u.changePassword(encoder.encode(newPassword));
            // Receiving the link proves control of the mailbox.
            u.markEmailVerified(Instant.now());
            users.save(u);
            db.update("update account_tokens set used_at=now() where user_id=? and purpose='EMAIL_VERIFICATION' and used_at is null", u.getId());
            db.update("update account_tokens set used_at=now() where user_id=? and purpose='PASSWORD_RESET' and used_at is null", u.getId());
            return u.getId();
        });
        // Every session opened with the old password ends now.
        revocations.revoke(userId);
        log.info("Password reset completed");
    }

    public void verifyEmail(String token) {
        tx.executeWithoutResult(t -> {
            User u = consume(token, "EMAIL_VERIFICATION");
            u.markEmailVerified(Instant.now());
            users.save(u);
        });
    }

    /** Atomically spends a valid token: a second use, an expired or an unknown token all fail the same way. */
    private User consume(String token, String purpose) {
        List<UUID> owner = token == null || token.isBlank() || token.length() > 200 ? List.of()
                : db.queryForList("update account_tokens set used_at=now() where token_hash=? and purpose=? "
                        + "and used_at is null and expires_at>now() returning user_id", UUID.class, tokens.hash(token), purpose);
        if (owner.isEmpty()) throw new BusinessRuleException("The link is invalid or has expired");
        return users.findById(owner.getFirst()).filter(User::isEnabled)
                .orElseThrow(() -> new BusinessRuleException("The link is invalid or has expired"));
    }

    private boolean enqueue(String email, String purpose, Duration ttl) {
        String normalized = User.normalizeEmail(email);
        // Same address budget and persistence path whether an account exists or not.
        if (limits.retryAfter("account-mail:" + purpose + ":" + tokens.hash(normalized), 3, Duration.ofMinutes(15)) > 0) return false;
        db.update("insert into account_emails(id,email,purpose,expires_at) values (?,?,?,?)", UUID.randomUUID(),
                normalized, purpose, java.sql.Timestamp.from(Instant.now().plus(ttl)));
        return true;
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-delay:60000}", initialDelayString = "${app.mail.initial-delay:30000}")
    public void deliverEmails() {
        db.update("delete from pending_registrations p using account_emails m where p.id=m.id and m.expires_at<=now()");
        db.update("delete from account_emails where expires_at<now()-interval '7 days'");
        db.update("delete from account_tokens where expires_at<now()-interval '7 days'");
        db.update("update account_emails set status='FAILED',last_error='EXPIRED',claimed_until=null,claim_id=null "
                + "where status in ('PENDING','PROCESSING') and expires_at<=now()");
        db.update("update account_emails set status='FAILED',last_error='CLAIM_EXPIRED',claimed_until=null,claim_id=null "
                + "where status='PROCESSING' and claimed_until<now() and attempts>=?", MAX_ATTEMPTS);
        if (!verificationAvailable()) return;
        // Claim only when ready to send, so queued jobs never spend their lease behind an earlier SMTP call.
        for (int i = 0; i < 10; i++) {
            var jobs = db.queryForList("""
                    update account_emails e set status='PROCESSING', attempts=attempts+1,
                        claimed_until=now()+interval '5 minutes',claim_id=gen_random_uuid()
                    where id in (select id from account_emails where attempts<? and expires_at>now()
                        and ((status='PENDING' and next_attempt_at<=now()) or (status='PROCESSING' and claimed_until<now()))
                        order by next_attempt_at,id limit 1 for update skip locked)
                    returning id,email,purpose,attempts,expires_at,claim_id""", MAX_ATTEMPTS);
            if (jobs.isEmpty()) return;
            deliver(jobs.getFirst());
        }
    }

    private record Message(String email, String firstName, String purpose, String token) {}

    private void deliver(Map<String, Object> job) {
        UUID id = (UUID) job.get("id");
        Object claim = job.get("claim_id");
        int attempts = ((Number) job.get("attempts")).intValue();
        try {
            Message message = tx.execute(t -> {
                Boolean owned = db.queryForObject("select exists(select 1 from account_emails where id=? "
                        + "and status='PROCESSING' and claim_id=? and expires_at>now())", Boolean.class, id, claim);
                if (!Boolean.TRUE.equals(owned)) return null;
                String purpose = (String) job.get("purpose");
                if ("REGISTRATION_CODE".equals(purpose)) {
                    String email = (String) job.get("email");
                    Boolean pending = db.queryForObject("select exists(select 1 from pending_registrations where id=? and failed_attempts<5)",
                            Boolean.class, id);
                    return Boolean.TRUE.equals(pending) && !users.existsByEmailIgnoreCase(email)
                            ? new Message(email, "", purpose, tokens.registrationCode(id)) : null;
                }
                var user = users.findByEmailIgnoreCase((String) job.get("email"))
                        .filter(u -> u.isEnabled() && (!"EMAIL_VERIFICATION".equals(purpose) || !u.isEmailVerified()));
                if (user.isEmpty()) return null;
                User u = user.get();
                String token = tokens.accountMailToken(id);
                // Never revoke a previously delivered link merely because a newer SMTP attempt may fail.
                // Consuming a reset revokes all reset links; every link still has its own expiry and single use.
                db.update("insert into account_tokens(id,user_id,purpose,token_hash,expires_at) values (?,?,?,?,?) "
                                + "on conflict(id) do nothing", id, u.getId(), purpose, tokens.hash(token), job.get("expires_at"));
                Boolean usable = db.queryForObject("select exists(select 1 from account_tokens where id=? "
                        + "and token_hash=? and used_at is null and expires_at>now())", Boolean.class, id, tokens.hash(token));
                return Boolean.TRUE.equals(usable) ? new Message(u.getEmail(), u.getFirstName(), purpose, token) : null;
            });
            if (message == null) {
                db.update("update account_emails set status='SKIPPED',last_error='NO_ELIGIBLE_LINK',claimed_until=null,claim_id=null "
                        + "where id=? and status='PROCESSING' and claim_id=?", id, claim);
                return;
            }
            send(message);
            db.update("update account_emails set status='SENT',sent_at=now(),last_error=null,claimed_until=null,claim_id=null "
                    + "where id=? and status='PROCESSING' and claim_id=?", id, claim);
        } catch (RuntimeException e) {
            long minutes = Math.min(60, 1L << Math.min(attempts - 1, 6));
            db.update("update account_emails set status=?,last_error=?,claimed_until=null,claim_id=null,"
                            + "next_attempt_at=now()+make_interval(mins => ?) where id=? and status='PROCESSING' and claim_id=?",
                    attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING", e.getClass().getSimpleName(), (int) minutes, id, claim);
            log.warn("Account email {} deferred after attempt {} ({})", id, attempts, e.getClass().getSimpleName());
        }
    }

    private void send(Message message) {
        if ("REGISTRATION_CODE".equals(message.purpose())) {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(message.email());
            m.setSubject("Tu código de confirmación · " + store);
            m.setText("Ingresa este código en el formulario donde empezaste tu registro en " + store
                    + ":\n\n" + message.token() + "\n\nEl código es de un solo uso y vence 15 minutos después de iniciar el registro."
                    + "\n\nSi no solicitaste el registro, ignora este correo.\n");
            mail.send(m);
            return;
        }
        boolean reset = "PASSWORD_RESET".equals(message.purpose());
        String path = reset ? "/account/reset" : "/account/verify";
        String subject = reset ? "Restablece tu contraseña" : "Confirma tu correo";
        String intro = reset ? "Recibimos una solicitud para restablecer tu contraseña. Este enlace es de un solo uso "
                + "y vence 30 minutos después de la solicitud:" : "Confirma que este correo te pertenece. El enlace vence 48 horas después de la solicitud:";
        String outro = reset ? "Si no fuiste tú, ignora este mensaje: tu contraseña no cambia."
                : "Si no creaste una cuenta, ignora este mensaje.";
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(message.email());
        m.setSubject(subject + " · " + store);
        // The token travels in the URL fragment so it never reaches server logs or Referer headers.
        m.setText("Hola " + message.firstName() + ",\n\n" + intro + "\n\n" + frontendUrl + path + "#token=" + message.token()
                + "\n\n" + outro + "\n\n" + store + "\n");
        mail.send(m);
    }

}
