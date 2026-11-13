package com.velora.identity.application;

import com.velora.identity.application.IdentityDtos.AddressRequest;
import com.velora.identity.application.IdentityDtos.AddressView;
import com.velora.identity.application.IdentityDtos.RegisterRequest;
import com.velora.identity.application.IdentityDtos.UpdateProfileRequest;
import com.velora.identity.application.IdentityDtos.UserView;
import com.velora.identity.domain.Address;
import com.velora.identity.domain.Role;
import com.velora.identity.domain.User;
import com.velora.identity.infrastructure.AddressRepository;
import com.velora.identity.infrastructure.UserRepository;
import com.velora.shared.domain.BusinessRuleException;
import com.velora.shared.domain.ConflictException;
import com.velora.shared.domain.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final int MAX_ADDRESSES = 10;
    private final UserRepository users;
    private final AddressRepository addresses;
    private final PasswordEncoder encoder;

    public AccountService(UserRepository users, AddressRepository addresses, PasswordEncoder encoder) {
        this.users = users;
        this.addresses = addresses;
        this.encoder = encoder;
    }

    /** Public registration always creates CUSTOMER accounts. */
    @Transactional
    public UserView register(RegisterRequest req) {
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw new ConflictException("Email already registered");
        }
        User user = users.save(new User(req.email(), encoder.encode(req.password()), req.firstName(),
                req.lastName(), blankToNull(req.phone()), Role.CUSTOMER));
        return toView(user);
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

    @Transactional(readOnly = true)
    public List<AddressView> addresses(UUID userId) {
        return addresses.findByUserIdOrderByCreatedAtAsc(userId).stream().map(AccountService::toView).toList();
    }

    @Transactional
    public AddressView addAddress(UUID userId, AddressRequest req) {
        if (addresses.countByUserId(userId) >= MAX_ADDRESSES) {
            throw new BusinessRuleException("Address limit reached");
        }
        boolean makeDefault = req.makeDefault() || addresses.countByUserId(userId) == 0;
        if (makeDefault) {
            addresses.findByUserIdAndDefaultAddressTrue(userId).ifPresent(a -> {
                a.unsetDefault();
                addresses.flush();
            });
        }
        Address saved = addresses.save(new Address(userId, blankToNull(req.label()), req.recipientName(), req.line1(),
                blankToNull(req.line2()), req.city(), req.state(), req.postalCode(), req.country(),
                blankToNull(req.phone()), makeDefault));
        return toView(saved);
    }

    @Transactional
    public void deleteAddress(UUID userId, UUID addressId) {
        Address address = addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new NotFoundException("Address not found"));
        addresses.delete(address);
    }

    private User load(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    static UserView toView(User u) {
        return new UserView(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getPhone(),
                u.getRole().name(), u.getCreatedAt());
    }

    static AddressView toView(Address a) {
        return new AddressView(a.getId(), a.getLabel(), a.getRecipientName(), a.getLine1(), a.getLine2(),
                a.getCity(), a.getState(), a.getPostalCode(), a.getCountry(), a.getPhone(), a.isDefaultAddress());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
