package com.charitha.payments.api;

import com.charitha.payments.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService paymentService;
    public PaymentController(PaymentService paymentService) { this.paymentService = paymentService; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse create(@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody CreatePaymentRequest request) {
        return PaymentResponse.from(paymentService.create(idempotencyKey, request));
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable UUID paymentId) { return PaymentResponse.from(paymentService.get(paymentId)); }
}
