package com.charitha.audit.api;

import com.charitha.audit.service.AuditEventNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class AuditApiExceptionHandler {

    @ExceptionHandler(AuditEventNotFoundException.class)
    ResponseEntity<AuditApiError> handleNotFound(AuditEventNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new AuditApiError(
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                exception.getMessage(),
                Instant.now()
        ));
    }
}
