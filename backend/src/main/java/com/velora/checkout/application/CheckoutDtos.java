package com.velora.checkout.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class CheckoutDtos {
    private CheckoutDtos() {
    }

    public record CustomerInput(@NotBlank @Email @Size(max = 320) String email,
                                @NotBlank @Size(max = 100) String firstName,
                                @NotBlank @Size(max = 100) String lastName,
                                @Size(max = 30) @Pattern(regexp = "^[0-9+()\\-\\s]*$") String phone) {
    }

    public record AddressInput(@NotBlank @Size(max = 200) String recipientName,
                               @NotBlank @Size(max = 200) String line1,
                               @Size(max = 200) String line2,
                               @NotBlank @Size(max = 100) String city,
                               @NotBlank @Size(max = 100) String state,
                               @NotBlank @Size(max = 20) String postalCode,
                               @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code") String country,
                               @Size(max = 30) String phone) {
    }

    public record QuoteRequest(@NotNull UUID cartId, @NotNull @Valid CustomerInput customer,
                               @NotNull @Valid AddressInput shippingAddress) {
    }

    public record PlaceOrderRequest(@NotNull UUID quoteId) {
    }
}
