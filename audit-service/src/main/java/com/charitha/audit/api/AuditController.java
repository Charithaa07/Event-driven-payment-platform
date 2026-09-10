package com.charitha.audit.api;

import com.charitha.audit.domain.AuditEvent;
import com.charitha.audit.service.AuditIntegrityService;
import com.charitha.audit.service.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
@SecurityRequirement(name = "bearerAuth")
public class AuditController {
    private final AuditQueryService queryService;
    private final AuditIntegrityService integrityService;

    public AuditController(AuditQueryService queryService,
                           AuditIntegrityService integrityService) {
        this.queryService = queryService;
        this.integrityService = integrityService;
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(summary = "Get the immutable audit timeline for a payment",
            description = "Requires the audit:read OAuth scope. Raw payloads are omitted from timeline responses.")
    public List<AuditEventSummary> paymentTimeline(@PathVariable UUID paymentId) {
        return queryService.paymentTimeline(paymentId).stream()
                .map(AuditEventSummary::from)
                .toList();
    }

    @GetMapping("/events/{eventId}")
    @Operation(summary = "Get one audit event with raw payload and integrity result",
            description = "Requires the audit:read OAuth scope.")
    public AuditEventDetail event(@PathVariable UUID eventId) {
        AuditEvent event = queryService.event(eventId);
        return AuditEventDetail.from(event, integrityService.verify(event));
    }
}
