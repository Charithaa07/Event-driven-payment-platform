package com.charitha.notifications.api;

import com.charitha.notifications.service.NotificationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class NotificationApiExceptionHandler {

    @ExceptionHandler(NotificationNotFoundException.class)
    ResponseEntity<NotificationApiError> notFound(NotificationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new NotificationApiError(
                "NOTIFICATION_NOT_FOUND",
                ex.getMessage(),
                Instant.now()
        ));
    }
}
