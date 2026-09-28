package io.github.hipstermin.idem.hub.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** 1.1: authz 아웃박스 피드 한 건 ({@code GET /api/v1/internal/authz/events}). payload 는 {@code AuthorizationEvent} JSON. */
public record AuthzEventRecord(String eventId, String eventType, String aggregateId, Long eventVersion,
                               Instant createdAt, JsonNode payload) {}
