package com.charitha.audit.service;

import com.charitha.audit.domain.AuditEvent;
import com.charitha.audit.domain.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AuditQueryService {
    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> paymentTimeline(UUID paymentId) {
        return repository.findByAggregateIdOrderByOccurredAtAscRecordedAtAsc(paymentId);
    }

    @Transactional(readOnly = true)
    public AuditEvent event(UUID eventId) {
        return repository.findById(eventId)
                .orElseThrow(() -> new AuditEventNotFoundException(eventId));
    }
}
