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
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Password recovery and email verification with single-use, expiring links. Only the HMAC of a token is stored.
 * Requests never reveal whether an address has an account: the work happens off the request thread and the
 * caller always gets the same answer.
 */
@Service
public class AccountRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AccountRecoveryService.class);
    private static final Duration RESET_TTL = Duration.ofMinutes(30);
    private static final Duration VERIFY_TTL = Duration.ofHours(48);

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
    /** Bounded: a flood of requests is dropped instead of queued without limit. */
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200), r -> {
                Thread t = new Thread(r, "account-mail");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardPolicy());

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

    /** Always returns immediately and silently, whether or not the address belongs to an account. */
    public void requestPasswordReset(String email) {
        String normalized = User.normalizeEmail(email);
        worker.execute(() -> guarded(() -> users.findByEmailIgnoreCase(normalized).filter(User::isEnabled)
                .ifPresent(u -> issue(u, "PASSWORD_RESET", RESET_TTL, "/account/reset", "Restablece tu contraseña",
                        "Recibimos una solicitud para restablecer la contraseña de tu cuenta. El enlace vence en 30 minutos "
                                + "y solo puede usarse una vez:",
                        "Si no fuiste tú, ignora este mensaje: tu contraseña no cambia."))));
    }

    public void requestVerification(UUID userId) {
        worker.execute(() -> guarded(() -> users.findById(userId).filter(u -> u.isEnabled() && !u.isEmailVerified())
                .ifPresent(u -> issue(u, "EMAIL_VERIFICATION", VERIFY_TTL, "/account/verify", "Confirma tu correo",
                        "Confirma que este correo te pertenece para proteger tu cuenta. El enlace vence en 48 horas:",
                        "Si no creaste una cuenta, ignora este mensaje."))));
    }

    public void resetPassword(String token, String newPassword) {
        UUID userId = tx.execute(t -> {
            User u = consume(token, "PASSWORD_RESET");
            u.changePassword(encoder.encode(newPassword));
            // Receiving the link proves control of the mailbox.
            u.markEmailVerified(Instant.now());
            users.save(u);
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

    private void issue(User u, String purpose, Duration ttl, String path, String subject, String intro, String outro) {
        if (mail == null || from.isBlank() || frontendUrl.isBlank()) {
            log.warn("Account email not sent: SMTP, APP_MAIL_FROM or APP_PUBLIC_FRONTEND_URL is not configured");
            return;
        }
        // Per-account budget, independent of the caller's address.
        if (limits.retryAfter("account-mail:" + purpose + ":" + u.getId(), 3, Duration.ofMinutes(15)) > 0) return;
        String token = tokens.newToken();
        tx.executeWithoutResult(t -> {
            db.update("update account_tokens set used_at=now() where user_id=? and purpose=? and used_at is null", u.getId(), purpose);
            db.update("insert into account_tokens(id,user_id,purpose,token_hash,expires_at) values (?,?,?,?,?)", UUID.randomUUID(),
                    u.getId(), purpose, tokens.hash(token), java.sql.Timestamp.from(Instant.now().plus(ttl)));
            db.update("delete from account_tokens where expires_at < now() - interval '7 days'");
        });
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(u.getEmail());
        m.setSubject(subject + " · " + store);
        // The token travels in the URL fragment so it never reaches server logs or Referer headers.
        m.setText("Hola " + u.getFirstName() + ",\n\n" + intro + "\n\n" + frontendUrl + path + "#token=" + token
                + "\n\n" + outro + "\n\n" + store + "\n");
        mail.send(m);
    }

    private static void guarded(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.warn("Account email failed ({})", e.getClass().getSimpleName());
        }
    }
}
