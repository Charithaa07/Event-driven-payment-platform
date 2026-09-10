package com.charitha.notifications.api;

import com.charitha.notifications.service.NotificationQueryService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {
    private final NotificationQueryService queryService;

    public NotificationController(NotificationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{notificationId}")
    public NotificationView get(@PathVariable UUID notificationId) {
        return queryService.get(notificationId);
    }

    @GetMapping
    public List<NotificationView> byPayment(@RequestParam UUID paymentId) {
        return queryService.byPayment(paymentId);
    }
}
