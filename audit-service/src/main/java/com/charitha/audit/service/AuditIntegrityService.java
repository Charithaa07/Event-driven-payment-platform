package com.charitha.audit.service;

import com.charitha.audit.domain.AuditEvent;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuditIntegrityService {

    public String compute(UUID eventId,
                          String eventType,
                          UUID aggregateId,
                          String customerId,
                          String sourceTopic,
                          int sourcePartition,
                          long sourceOffset,
                          String payload) {
        String material = String.join("\n",
                eventId.toString(),
                eventType,
                aggregateId.toString(),
                customerId,
                sourceTopic,
                Integer.toString(sourcePartition),
                Long.toString(sourceOffset),
                payload
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public boolean verify(AuditEvent event) {
        String expected = compute(
                event.getEventId(),
                event.getEventType(),
                event.getAggregateId(),
                event.getCustomerId(),
                event.getSourceTopic(),
                event.getSourcePartition(),
                event.getSourceOffset(),
                event.getPayload()
        );
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                event.getRecordSha256().getBytes(StandardCharsets.US_ASCII)
        );
    }
}
