package com.charitha.payments.api;

import com.charitha.payments.service.IdempotencyConflictException;
import com.charitha.payments.service.PaymentNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(PaymentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleNotFound(PaymentNotFoundException ex) {
        return ApiErrorResponse.from(ex);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ApiErrorResponse.from(ex);
    }
}
