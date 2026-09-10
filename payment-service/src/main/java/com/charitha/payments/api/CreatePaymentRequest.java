package com.charitha.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

@Schema(description = "Payment creation request. Customer identity is taken from the authenticated JWT subject.")
public record CreatePaymentRequest(
        @Schema(description = "Payment amount with at most two fractional digits", example = "42.50", minimum = "0.01")
        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount,

        @Schema(description = "Three-letter uppercase ISO-style currency code", example = "USD", pattern = "[A-Z]{3}")
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}")
        String currency
) {}
