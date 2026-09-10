package com.charitha.transactions.recovery;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class RecoveryApiExceptionHandler {

    @ExceptionHandler(DeadLetterEventNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, Object> notFound(DeadLetterEventNotFoundException ex) {
        return error(ex);
    }

    @ExceptionHandler(DltReplayInProgressException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, Object> replayInProgress(DltReplayInProgressException ex) {
        return error(ex);
    }

    @ExceptionHandler(DltReplayFailedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, Object> replayFailed(DltReplayFailedException ex) {
        return error(ex);
    }

    private Map<String, Object> error(RuntimeException ex) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "error", ex.getMessage()
        );
    }
}
