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
                "USD",
                "customer-1"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsMoneyBeyondDatabasePrecisionAndScale() {
        CreatePaymentRequest excessiveScale = new CreatePaymentRequest(
                new BigDecimal("42.501"),
                "USD",
                "customer-1"
        );
        CreatePaymentRequest excessiveIntegerDigits = new CreatePaymentRequest(
                new BigDecimal("123456789012345678.00"),
                "USD",
                "customer-1"
        );

        assertFalse(validator.validate(excessiveScale).isEmpty());
        assertFalse(validator.validate(excessiveIntegerDigits).isEmpty());
    }

    @Test
    void rejectsCustomerIdBeyondDatabaseColumnLength() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                new BigDecimal("42.50"),
                "USD",
                "x".repeat(121)
        );

        assertFalse(validator.validate(request).isEmpty());
    }
}
