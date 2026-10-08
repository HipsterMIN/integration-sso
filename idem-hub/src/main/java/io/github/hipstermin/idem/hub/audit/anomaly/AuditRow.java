package io.github.hipstermin.idem.hub.audit.anomaly;

import java.time.Instant;

/** 점수기가 읽는 감사 행 — metadata 는 읽지 않는다(규칙은 분류·행위·결과·축만 본다). */
public record AuditRow(String auditId, String category, String action, String actorType, String actorId,
                       String agencyCode, String sourceIp, String correlationId, String outcome, String outcomeDetail,
                       Instant occurredAt) {}
