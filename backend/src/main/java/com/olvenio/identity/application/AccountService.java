package com.olvenio.identity.application;

import com.olvenio.catalog.infrastructure.CategoryRepository;
import com.olvenio.identity.application.GoogleIdentityVerifier.GoogleIdentity;
import com.olvenio.identity.application.IdentityDtos.AddressRequest;
import com.olvenio.identity.application.IdentityDtos.AddressView;
import com.olvenio.identity.application.IdentityDtos.UpdateProfileRequest;
import com.olvenio.identity.application.IdentityDtos.UserView;
import com.olvenio.identity.domain.Address;
import com.olvenio.identity.domain.Role;
import com.olvenio.identity.domain.User;
import com.olvenio.identity.infrastructure.AddressRepository;
import com.olvenio.identity.infrastructure.UserRepository;
import com.olvenio.shared.address.PostalDirectory;
import com.olvenio.shared.domain.BusinessRuleException;
import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.domain.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final String TERMS_VERSION = "1";
    private static final int MAX_ADDRESSES = 10;
    private final UserRepository users;
    private final AddressRepository addresses;
    private final PasswordEncoder encoder;
    private final PostalDirectory postalDirectory;

    private final CategoryRepository categories;

    public AccountService(UserRepository users, AddressRepository addresses, PasswordEncoder encoder,
                          PostalDirectory postalDirectory, CategoryRepository categories) {
        this.categories = categories;
        this.users = users;
        this.addresses = addresses;
        this.encoder = encoder;
        this.postalDirectory = postalDirectory;
    }

    @Transactional(readOnly = true)
    public void requireAvailableEmail(String email) {
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Email already registered");
        }
    }

    /** Called after proving ownership of the email in the registration transaction. */
    @Transactional
    public UserView registerVerified(String email, String passwordHash, String firstName, String lastName,
                                     String phone, Instant termsAcceptedAt) {
        requireAvailableEmail(email);
        User user = new User(email, passwordHash, firstName, lastName, blankToNull(phone), Role.CUSTOMER);
        user.acceptTerms(TERMS_VERSION, termsAcceptedAt);
        user.markEmailVerified(Instant.now());
        return toView(users.save(user));
    }

    @Transactional(readOnly = true)
    public UserView googleLogin(GoogleIdentity identity) {
        var linked = users.findByGoogleSubject(identity.subject());
        if (linked.isPresent()) {
            User user = linked.get();
            requireCustomer(user);
            return toView(user);
        }
        if (users.existsByEmailIgnoreCase(identity.email())) throw existingGoogleAccount();
        requireGoogleManagedEmail(identity);
        return null;
    }

    @Transactional
    public UserView googleRegister(GoogleIdentity identity, IdentityDtos.GoogleRegistration req) {
        if (users.existsByEmailIgnoreCase(identity.email()) || users.findByGoogleSubject(identity.subject()).isPresent()) {
            throw existingGoogleAccount();
        }
        requireGoogleManagedEmail(identity);
        Instant now = Instant.now();
        User user = new User(identity.email(), null, req.firstName(), req.lastName(), null, Role.CUSTOMER);
        user.linkGoogle(identity.subject());
        user.acceptTerms(TERMS_VERSION, now);
        user.markEmailVerified(now);
        return toView(users.save(user));
    }

    @Transactional
    public UserView linkGoogle(UUID userId, GoogleIdentity identity, String password) {
        User user = users.lockById(userId).orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        requireCustomer(user);
        if (user.getPasswordHash() == null || !encoder.matches(password, user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        if (!user.getEmail().equals(identity.email())) {
            throw new BusinessRuleException("Google and Olvenio email addresses must match");
        }
        if (!identity.googleManagesEmail() && !user.isEmailVerified()) {
            throw new BusinessRuleException("Confirm your Olvenio email before linking Google");
        }
        if (identity.subject().equals(user.getGoogleSubject())) return toView(user);
        if (user.getGoogleSubject() != null || users.findByGoogleSubject(identity.subject()).isPresent()) {
            throw new ConflictException("Google account already linked");
        }
        user.linkGoogle(identity.subject());
        return toView(user);
    }

    private static ConflictException existingGoogleAccount() {
        return new ConflictException("Sign in to your existing Olvenio account before linking Google");
    }

    private static void requireGoogleManagedEmail(GoogleIdentity identity) {
        if (!identity.googleManagesEmail()) {
            throw new BusinessRuleException("Register and confirm your email with Olvenio before linking Google");
        }
    }

    private static void requireCustomer(User user) {
        if (!user.isEnabled() || user.getRole() != Role.CUSTOMER) {
            throw new BadCredentialsException("Invalid credentials");
        }
    }

    @Transactional(readOnly = true)
    public UserView profile(UUID userId) {
        return toView(load(userId));
    }

    @Transactional
    public UserView updateProfile(UUID userId, UpdateProfileRequest req) {
        User user = load(userId);
        user.updateProfile(req.firstName(), req.lastName(), req.phone());
        return toView(user);
    }

    @Transactional
    public UserView updateInterests(UUID userId, Set<UUID> ids) {
        User user = load(userId);
        if (categories.findAllById(ids).size() != ids.size()) {
            throw new BusinessRuleException("One or more categories no longer exist");
        }
        user.updateCategoryInterests(ids);
        return toView(user);
    }

    @Transactional(readOnly = true)
    public List<AddressView> addresses(UUID userId) {
        return addresses.findByUserIdOrderByCreatedAtAsc(userId).stream().map(AccountService::toView).toList();
    }

    @Transactional
    public AddressView addAddress(UUID userId, AddressRequest req) {
        lockAddressBook(userId);
        long count = addresses.countByUserId(userId);
        if (count >= MAX_ADDRESSES) {
            throw new BusinessRuleException("Address limit reached");
        }
        Place place = place(req);
        boolean makeDefault = req.makeDefault() || count == 0;
        if (makeDefault) clearDefaultAddress(userId);
        Address saved = addresses.save(new Address(userId, blankToNull(req.label()), req.recipientName().trim(),
                req.line1().trim(), blankToNull(req.line2()), place.city(), place.state(), req.postalCode(),
                req.country(), blankToNull(req.phone()), makeDefault, place.neighborhood()));
        return toView(saved);
    }

    @Transactional
    public AddressView updateAddress(UUID userId, UUID addressId, AddressRequest req) {
        lockAddressBook(userId);
        Address address = loadAddress(userId, addressId);
        Place place = place(req);
        if (req.makeDefault()) makeDefault(address);
        address.update(blankToNull(req.label()), req.recipientName().trim(), req.line1().trim(),
                blankToNull(req.line2()), place.city(), place.state(), req.postalCode(), req.country(),
                blankToNull(req.phone()), place.neighborhood());
        return toView(address);
    }

    @Transactional
    public AddressView setDefaultAddress(UUID userId, UUID addressId) {
        lockAddressBook(userId);
        Address address = loadAddress(userId, addressId);
        makeDefault(address);
        return toView(address);
    }

    @Transactional
    public void deleteAddress(UUID userId, UUID addressId) {
        lockAddressBook(userId);
        Address address = loadAddress(userId, addressId);
        boolean wasDefault = address.isDefaultAddress();
        addresses.delete(address);
        addresses.flush();
        if (wasDefault) {
            // Keep a default while the book has addresses: promote the oldest remaining one.
            addresses.findByUserIdOrderByCreatedAtAsc(userId).stream().findFirst().ifPresent(Address::markDefault);
        }
    }

    private record Place(String city, String state, String neighborhood) {}

    /** Mexican addresses take municipality and state from the postal directory, as checkout does. */
    private Place place(AddressRequest req) {
        String neighborhood = blankToNull(req.neighborhood());
        if (!"MX".equals(req.country())) return new Place(req.city().trim(), req.state().trim(), neighborhood);
        var postal = postalDirectory.validate(req.postalCode(), req.state(), req.city(), neighborhood);
        return new Place(postal.municipality(), postal.state(), neighborhood);
    }

    private void lockAddressBook(UUID userId) {
        users.lockById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    private Address loadAddress(UUID userId, UUID addressId) {
        return addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new NotFoundException("Address not found"));
    }

    private void makeDefault(Address address) {
        if (address.isDefaultAddress()) return;
        clearDefaultAddress(address.getUserId());
        address.markDefault();
    }

    private void clearDefaultAddress(UUID userId) {
        addresses.findByUserIdAndDefaultAddressTrue(userId).ifPresent(previous -> {
            previous.unsetDefault();
            // Release the partial unique index entry before another address takes it.
            addresses.flush();
        });
    }

    private User load(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    static UserView toView(User u) {
        return new UserView(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getPhone(),
                u.getRole().name(), u.getCreatedAt(), u.isEmailVerified(), u.getCategoryInterests().stream().sorted().toList(),
                u.getGoogleSubject() != null);
    }

    static AddressView toView(Address a) {
        return new AddressView(a.getId(), a.getLabel(), a.getRecipientName(), a.getLine1(), a.getLine2(),
                a.getCity(), a.getState(), a.getPostalCode(), a.getCountry(), a.getPhone(), a.isDefaultAddress(), a.getNeighborhood());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
