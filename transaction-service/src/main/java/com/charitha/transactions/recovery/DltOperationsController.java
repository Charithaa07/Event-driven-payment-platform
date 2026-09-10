package com.charitha.transactions.recovery;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/dlt")
public class DltOperationsController {
    private final DeadLetterStore store;
    private final DltReplayService replayService;

    public DltOperationsController(DeadLetterStore store,
                                   DltReplayService replayService) {
        this.store = store;
        this.replayService = replayService;
    }

    @GetMapping
    public List<DeadLetterEventSummary> list(
            @RequestParam(defaultValue = "PENDING") DeadLetterStatus status) {
        return store.list(status).stream()
                .map(DeadLetterEventSummary::from)
                .toList();
    }

    @GetMapping("/{eventId}")
    public DeadLetterEventDetail get(@PathVariable UUID eventId) {
        return DeadLetterEventDetail.from(store.get(eventId));
    }

    @PostMapping("/{eventId}/replay")
    public DeadLetterEventDetail replay(
            @PathVariable UUID eventId,
            @AuthenticationPrincipal Jwt jwt) {
        return DeadLetterEventDetail.from(replayService.replay(eventId, jwt.getSubject()));
    }
}
