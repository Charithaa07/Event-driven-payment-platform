package com.charitha.payments.api;

import com.charitha.payments.config.OpenApiConfig;
import com.charitha.payments.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Authenticated payment creation and retrieval")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create a payment",
            description = """
                    Creates a payment for the authenticated customer. Requires the `payments:write` scope.
                    `customerId` is never accepted from the payload; ownership comes from the JWT `sub` claim.
                    Repeating the same request with the same customer-scoped idempotency key returns the original payment.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Payment accepted",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PaymentResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request body or idempotency header", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token", content = @Content),
            @ApiResponse(responseCode = "403", description = "Bearer token does not include `payments:write`", content = @Content),
            @ApiResponse(
                    responseCode = "409",
                    description = "The idempotency key was already used for different payment details by this customer",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public PaymentResponse create(
            @Parameter(
                    description = "Customer-scoped idempotency key used to make retried create requests safe",
                    required = true,
                    example = "checkout-7f41d"
            )
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return PaymentResponse.from(paymentService.create(idempotencyKey, request, jwt.getSubject()));
    }

    @GetMapping("/{paymentId}")
    @Operation(
            summary = "Retrieve a payment",
            description = "Returns a payment only when it belongs to the authenticated JWT subject. Requires `payments:read`."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Payment returned",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PaymentResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Malformed payment identifier", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token", content = @Content),
            @ApiResponse(responseCode = "403", description = "Bearer token does not include `payments:read`", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "Payment does not exist or belongs to another customer",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public PaymentResponse get(
            @Parameter(description = "Payment identifier", example = "6d834f55-9d6d-4ca5-873f-92327fd582c2")
            @PathVariable UUID paymentId,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return PaymentResponse.from(paymentService.get(paymentId, jwt.getSubject()));
    }
}
