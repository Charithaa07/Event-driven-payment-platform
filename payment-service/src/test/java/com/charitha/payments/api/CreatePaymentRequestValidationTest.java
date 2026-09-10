package com.charitha.payments.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreatePaymentRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validPaymentRequestPassesValidation() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                new BigDecimal("42.50"),
                "USD"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsMoneyBeyondDatabasePrecisionAndScale() {
        CreatePaymentRequest excessiveScale = new CreatePaymentRequest(
                new BigDecimal("42.501"),
                "USD"
        );
        CreatePaymentRequest excessiveIntegerDigits = new CreatePaymentRequest(
                new BigDecimal("123456789012345678.00"),
                "USD"
        );

        assertFalse(validator.validate(excessiveScale).isEmpty());
        assertFalse(validator.validate(excessiveIntegerDigits).isEmpty());
    }

    @Test
    void rejectsInvalidCurrencyCode() {
        assertFalse(validator.validate(new CreatePaymentRequest(new BigDecimal("42.50"), "usd")).isEmpty());
        assertFalse(validator.validate(new CreatePaymentRequest(new BigDecimal("42.50"), "US")).isEmpty());
    }
}
