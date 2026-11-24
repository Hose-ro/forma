package com.olvenio.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.olvenio.identity.application.GoogleIdentityVerifier.GoogleIdentity;
import com.olvenio.identity.application.IdentityDtos.GoogleRegistration;
import com.olvenio.identity.domain.Role;
import com.olvenio.identity.domain.User;
import com.olvenio.identity.infrastructure.UserRepository;
import com.olvenio.shared.domain.BusinessRuleException;
import com.olvenio.shared.domain.ConflictException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class GoogleAccountTest {
    @Mock UserRepository users;
    @Spy BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    @InjectMocks AccountService accounts;

    private final UUID id = UUID.randomUUID();
    private final GoogleIdentity identity = new GoogleIdentity("subject", "customer@gmail.com", "Test", "User", true);

    @Test void matchingEmailDoesNotSignInOrLinkAnExistingAccount() {
        when(users.existsByEmailIgnoreCase(identity.email())).thenReturn(true);
        assertThatThrownBy(() -> accounts.googleLogin(identity)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> accounts.googleRegister(identity, new GoogleRegistration(UUID.randomUUID(), "Test", "User", true)))
                .isInstanceOf(ConflictException.class);
    }

    @Test void customerMustConfirmPasswordBeforeLinkingAndKeepsTheSameAccount() {
        String passwordHash = passwords.encode("right-password");
        User customer = new User(identity.email(), passwordHash, "Test", "User", null, Role.CUSTOMER);
        when(users.lockById(id)).thenReturn(Optional.of(customer));
        assertThatThrownBy(() -> accounts.linkGoogle(id, identity, "wrong-password"))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(customer.getGoogleSubject()).isNull();

        assertThat(accounts.linkGoogle(id, identity, "right-password").googleLinked()).isTrue();
        assertThat(customer.getGoogleSubject()).isEqualTo(identity.subject());
        assertThat(customer.getPasswordHash()).isEqualTo(passwordHash);
        when(users.findByGoogleSubject(identity.subject())).thenReturn(Optional.of(customer));
        assertThat(accounts.googleLogin(identity).email()).isEqualTo(customer.getEmail());
    }

    @Test void externalGoogleEmailRequiresOlvenioEmailConfirmation() {
        var external = new GoogleIdentity("subject", "customer@example.com", "Test", "User", false);
        User customer = new User(external.email(), passwords.encode("right-password"), "Test", "User", null, Role.CUSTOMER);
        when(users.lockById(id)).thenReturn(Optional.of(customer));
        assertThatThrownBy(() -> accounts.googleLogin(external)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> accounts.linkGoogle(id, external, "right-password")).isInstanceOf(BusinessRuleException.class);
        assertThat(customer.getGoogleSubject()).isNull();
    }
}
