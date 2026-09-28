package io.github.hipstermin.idem.authz.api.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/** 1.1: 인가 이벤트 피드 한 건 — hub {@code AuthzEventPoller} 가 읽는다. payload 는 아웃박스에 적재된 JSON 그대로. */
public record AuthzEventResponse(String eventId, String eventType, String aggregateId, Long eventVersion,
                                 Instant createdAt, @JsonRawValue String payload) {}
