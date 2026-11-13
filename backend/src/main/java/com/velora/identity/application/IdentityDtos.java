package com.velora.identity.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class IdentityDtos {
    private IdentityDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Size(max = 30) @Pattern(regexp = "^[0-9+()\\-\\s]*$") String phone) {
        @AssertTrue(message = "password must not exceed 72 UTF-8 bytes")
        public boolean isPasswordWithinBcryptLimit() { return bcryptLength(password); }
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 320) String email,
                               @NotBlank @Size(max = 72) String password) {
        @AssertTrue(message = "password must not exceed 72 UTF-8 bytes")
        public boolean isPasswordWithinBcryptLimit() { return bcryptLength(password); }
    }

    private static boolean bcryptLength(String password) {
        return password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
    }

    public record UpdateProfileRequest(@Size(min = 1, max = 100) String firstName,
                                       @Size(min = 1, max = 100) String lastName,
                                       @Size(max = 30) @Pattern(regexp = "^[0-9+()\\-\\s]*$") String phone) {
    }

    public record UserView(UUID id, String email, String firstName, String lastName, String phone, String role,
                           Instant createdAt) {
    }

    public record AddressRequest(
            @Size(max = 60) String label,
            @NotBlank @Size(max = 200) String recipientName,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 100) String state,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code") String country,
            @Size(max = 30) String phone,
            boolean makeDefault) {
    }

    public record AddressView(UUID id, String label, String recipientName, String line1, String line2, String city,
                              String state, String postalCode, String country, String phone, boolean isDefault) {
    }
}
