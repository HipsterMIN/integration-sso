package io.github.hipstermin.idem.registry.api;

import io.github.hipstermin.idem.registry.api.dto.OutboxEventResponse;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.OutboxJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.OutboxJpaRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * D3: 사용자 변경 이벤트 내부 피드 — Kafka 없는 설치에서 hub 가 {@code idem.registry.user.events} 를 폴링한다.
 *
 * <p>{@code GET /api/v1/internal/events?topic=idem.registry.user.events&afterCreatedAt=…&afterEventId=…&limit=…}
 *
 * <p>읽기 전용이다: 아웃박스 상태(PENDING/PUBLISHED)를 바꾸지 않는다 — Kafka 릴레이가 켜진 설치와 폴링 설치가 같은 테이블을
 * 서로 다른 방식으로 소비해도 간섭하지 않는다. 순서는 {@code (created_at, event_id)} 키셋이며 hub 가 워터마크를 쥔다.
 * 보안: {@code X-Internal-Api-Key} (InternalApiKeyInterceptor, {@code /api/v1/internal/**}).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/events")
@RequiredArgsConstructor
public class OutboxEventsController {

    static final int MAX_LIMIT = 500;

    private final OutboxJpaRepository outboxRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<OutboxEventResponse>> list(
            @RequestParam(defaultValue = "idem.registry.user.events") String topic,
            @RequestParam(required = false) Instant afterCreatedAt,
            @RequestParam(required = false) String afterEventId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        Instant since = afterCreatedAt != null ? afterCreatedAt : Instant.EPOCH;
        String sinceId = afterEventId != null ? afterEventId : "";
        List<OutboxJpaEntity> rows = outboxRepository.findAfter(topic, since, sinceId, PageRequest.of(0, size));
        log.debug("[OutboxEvents] feed topic={} after={}({}) n={} cid={}", topic, since, sinceId, rows.size(), correlationId);
        return ResponseEntity.ok(rows.stream()
                .map(e -> new OutboxEventResponse(e.getEventId(), e.getEventType(), e.getAggregateId(),
                        e.getEventVersion(), e.getCreatedAt(), e.getPayload()))
                .toList());
    }
}
