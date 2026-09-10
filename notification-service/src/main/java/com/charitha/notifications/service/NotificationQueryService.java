package com.charitha.notifications.service;

import com.charitha.notifications.api.NotificationView;
import com.charitha.notifications.domain.NotificationDeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class NotificationQueryService {
    private final NotificationDeliveryRepository repository;

    public NotificationQueryService(NotificationDeliveryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public NotificationView get(UUID notificationId) {
        return repository.findById(notificationId)
                .map(NotificationView::from)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    }

    @Transactional(readOnly = true)
    public List<NotificationView> byPayment(UUID paymentId) {
        return repository.findByPaymentIdOrderByCreatedAtAsc(paymentId)
                .stream()
                .map(NotificationView::from)
                .toList();
    }
}
