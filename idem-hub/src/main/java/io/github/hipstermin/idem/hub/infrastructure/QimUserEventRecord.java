package io.github.hipstermin.idem.hub.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** D3: registry 이벤트 피드 한 건 ({@code GET /api/v1/internal/events}). {@code payload} 는 {@code UserEvent} JSON. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QimUserEventRecord(String eventId, String eventType, String aggregateId, Long eventVersion,
                                 Instant createdAt, JsonNode payload) {}
