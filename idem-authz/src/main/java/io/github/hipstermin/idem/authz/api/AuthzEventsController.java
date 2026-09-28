package io.github.hipstermin.idem.authz.api;

import io.github.hipstermin.idem.authz.api.dto.AuthzEventResponse;
import io.github.hipstermin.idem.authz.application.AuthzOutboxService;
import io.github.hipstermin.idem.authz.infrastructure.AuthzOutboxRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1: 인가 이벤트 내부 피드 — hub 가 {@code idem.authz.assignment.events} 를 폴링해 기관에 할당 변경을 통보한다
 * (registry 의 {@code OutboxEventsController} 와 같은 계약).
 *
 * <p>{@code GET /api/v1/internal/authz/events?afterCreatedAt=…&afterEventId=…&limit=…}
 *
 * <p>읽기 전용: 아웃박스 상태를 바꾸지 않는다. 순서는 {@code (created_at, event_id)} 키셋이며 워터마크는 hub 가 쥔다.
 * 보안: {@code X-Internal-Api-Key} ({@code InternalApiKeyInterceptor}, {@code /api/v1/internal/**}).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/authz/events")
@RequiredArgsConstructor
public class AuthzEventsController {

    static final int MAX_LIMIT = 500;

    private final AuthzOutboxRepository outboxRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<AuthzEventResponse>> list(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) Instant afterCreatedAt,
            @RequestParam(required = false) String afterEventId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String t = topic != null && !topic.isBlank() ? topic : AuthzOutboxService.TOPIC;
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        Instant since = afterCreatedAt != null ? afterCreatedAt : Instant.EPOCH;
        String sinceId = afterEventId != null ? afterEventId : "";
        var rows = outboxRepository.findAfter(t, since, sinceId, PageRequest.of(0, size));
        log.debug("[AuthzEvents] feed topic={} after={}({}) n={} cid={}", t, since, sinceId, rows.size(), correlationId);
        return ResponseEntity.ok(rows.stream()
                .map(e -> new AuthzEventResponse(e.getEventId(), e.getEventType(), e.getAggregateId(),
                        e.getEventVersion(), e.getCreatedAt(), e.getPayload()))
                .toList());
    }
}
