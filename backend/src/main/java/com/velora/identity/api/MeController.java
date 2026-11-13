package com.velora.identity.api;

import com.velora.identity.application.AccountService;
import com.velora.identity.application.IdentityDtos.AddressRequest;
import com.velora.identity.application.IdentityDtos.AddressView;
import com.velora.identity.application.IdentityDtos.UpdateProfileRequest;
import com.velora.identity.application.IdentityDtos.UserView;
import com.velora.shared.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Account")
public class MeController {

    private final AccountService accounts;

    public MeController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public UserView profile(@AuthenticationPrincipal AuthUser user) {
        return accounts.profile(user.id());
    }

    @PatchMapping
    public UserView update(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody UpdateProfileRequest req) {
        return accounts.updateProfile(user.id(), req);
    }

    @GetMapping("/addresses")
    public List<AddressView> addresses(@AuthenticationPrincipal AuthUser user) {
        return accounts.addresses(user.id());
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public AddressView addAddress(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody AddressRequest req) {
        return accounts.addAddress(user.id(), req);
    }

    @DeleteMapping("/addresses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAddress(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        accounts.deleteAddress(user.id(), id);
    }
}
