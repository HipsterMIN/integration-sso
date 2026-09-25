package io.github.hipstermin.idem.registry.api.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * D3: 내부 이벤트 피드 한 건 — {@code idem.registry.outbox} 레코드의 읽기 전용 투영.
 * {@code payload} 는 저장된 JSON 그대로(문자열이 아니라 객체로) 내려간다.
 */
public record OutboxEventResponse(String eventId, String eventType, String aggregateId, Long eventVersion,
                                  Instant createdAt, @JsonRawValue String payload) {}
