package com.charitha.notifications.service;

import com.charitha.notifications.domain.NotificationDelivery;
import com.charitha.notifications.domain.NotificationDeliveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationClaimService {
    private final NotificationDeliveryRepository repository;
    private final int batchSize;
    private final int maxAttempts;
    private final long leaseTimeoutMs;

    public NotificationClaimService(
            NotificationDeliveryRepository repository,
            @Value("${notifications.dispatch.batch-size:25}") int batchSize,
            @Value("${notifications.dispatch.max-attempts:4}") int maxAttempts,
            @Value("${notifications.dispatch.lease-timeout-ms:120000}") long leaseTimeoutMs) {
        this.repository = repository;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.leaseTimeoutMs = leaseTimeoutMs;
    }

    @Transactional
    public List<ClaimedNotification> claimDue(Instant now) {
        Instant staleBefore = now.minusMillis(leaseTimeoutMs);
        List<NotificationDelivery> candidates =
                repository.lockDueForDispatch(now, staleBefore, batchSize);
        List<ClaimedNotification> claimed = new ArrayList<>();

        for (NotificationDelivery delivery : candidates) {
            if (delivery.getAttemptCount() >= maxAttempts) {
                delivery.markFailed(
                        delivery.getProvider(),
                        "Delivery attempts exhausted before another provider call",
                        now
                );
                continue;
            }
            delivery.claim(now);
            claimed.add(ClaimedNotification.from(delivery));
        }
        return claimed;
    }
}
