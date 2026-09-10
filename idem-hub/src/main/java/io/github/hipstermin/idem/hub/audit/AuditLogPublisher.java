package io.github.hipstermin.idem.hub.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.common.util.UuidV7;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 플랫폼 전역 감사 로그 발행기 (IdO 전담)
 *
 * <p><b>설계 원칙</b>:
 * <ol>
 *   <li>DB 우선 저장 ({@code ido.audit_log}) — Kafka 발행 실패 시에도 감사 기록 보존</li>
 *   <li>Kafka 비동기 발행 ({@code platform.audit.log}) — @Async 처리</li>
 *   <li>감사 로그 실패는 비치명적 — 절대 서비스 흐름 차단 금지</li>
 *   <li>개인정보(CI/DN/이름) 포함 금지 — identifierHash, agencyCode만 허용</li>
 * </ol>
 *
 * <p><b>사용 예시</b>:
 * <pre>{@code
 * auditLogPublisher.publish(
 *     AuditLogPublisher.AuditEntry.builder()
 *         .eventCategory(AuditLogEvent.CATEGORY_HANDOFF)
 *         .eventAction("HANDOFF_ISSUED")
 *         .actorType(AuditLogEvent.ACTOR_USER)
 *         .actorId(qimUserId)
 *         .resourceType("TICKET")
 *         .resourceId(ticketId)
 *         .agencyCode(agencyCode)
 *         .correlationId(correlationId)
 *         .outcome(AuditLogEvent.OUTCOME_SUCCESS)
 *         .build()
 * );
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogPublisher {

    private static final String SOURCE_SYSTEM = "ido";
    private static final String AUDIT_TOPIC   = "platform.audit.log";

    private final JdbcTemplate                  jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper                  objectMapper;

    // F-03: Kafka 발행 On/Off (IDO_AUDIT_KAFKA_ENABLED)
    // false → Kafka 없는 환경에서 연결 오류 없음, 재처리 스케줄러도 건너뜀
    @Value("${ido.audit.kafka-publish-enabled:${IDO_AUDIT_KAFKA_ENABLED:true}}")
    private boolean kafkaPublishEnabled;

    // F-04: DB 저장 On/Off (IDO_AUDIT_DB_ENABLED)
    // false → ido.audit_log 테이블 없는 환경에서도 오류 없음
    // ⚠️ 운영에서 false 금지 — 컴플라이언스(개인정보보호법) 위반 가능
    @Value("${ido.audit.db-save-enabled:${IDO_AUDIT_DB_ENABLED:true}}")
    private boolean dbSaveEnabled;

    // ── 공개 API ───────────────────────────────────────────────────────────

    /**
     * 감사 이벤트 기록 (비동기 — 호출 스레드 블로킹 없음)
     * try-catch 로 감싸져 있어 예외가 서비스 흐름을 방해하지 않는다.
     */
    @Async("auditExecutor")
    public void publish(AuditEntry entry) {
        try {
            // F-03, F-04 모두 비활성이면 전체 건너뜀
            if (!dbSaveEnabled && !kafkaPublishEnabled) {
                log.trace("[AuditLogPublisher] DISABLED — DB·Kafka 모두 비활성. action={}", entry.eventAction());
                return;
            }

            String auditId = UuidV7.generate();
            String metadataJson = toJson(entry.metadata());

            // ① DB 저장 (F-04: at-most-once — 실패해도 계속)
            boolean dbSaved = false;
            if (dbSaveEnabled) {
                dbSaved = insertAuditLog(auditId, entry, metadataJson);
            } else {
                log.debug("[AuditLogPublisher] DB 저장 SKIP (IDO_AUDIT_DB_ENABLED=false): action={}", entry.eventAction());
            }

            // ② Kafka 발행 (F-03: 비동기 fire-and-forget)
            if (kafkaPublishEnabled) {
                publishToKafka(auditId, entry, metadataJson, dbSaved);
            }

        } catch (Exception e) {
            // 감사 로그 실패는 서비스 중단 금지
            log.error("[AuditLogPublisher] 감사 로그 기록 실패 (비치명적): correlationId={} action={} error={}",
                    entry.correlationId(), entry.eventAction(), e.getMessage());
        }
    }

    // ── 내부 구현 ──────────────────────────────────────────────────────────

    private boolean insertAuditLog(String auditId, AuditEntry entry, String metadataJson) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ido.audit_log (
                        audit_id, event_category, event_action,
                        actor_type, actor_id,
                        resource_type, resource_id,
                        agency_code, correlation_id, source_system, source_ip,
                        outcome, outcome_detail,
                        metadata, kafka_published,
                        occurred_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,NOW())
                    """,
                    auditId,
                    entry.eventCategory(), entry.eventAction(),
                    entry.actorType(), entry.actorId(),
                    entry.resourceType(), entry.resourceId(),
                    entry.agencyCode(), entry.correlationId(), SOURCE_SYSTEM, entry.sourceIp(),
                    entry.outcome(), entry.outcomeDetail(),
                    metadataJson, false
            );
            log.debug("[AuditLogPublisher] DB 저장 완료: auditId={} action={}", auditId, entry.eventAction());
            return true;
        } catch (Exception e) {
            log.warn("[AuditLogPublisher] DB 저장 실패: action={} error={}", entry.eventAction(), e.getMessage());
            return false;
        }
    }

    private void publishToKafka(String auditId, AuditEntry entry, String metadataJson, boolean dbSaved) {
        try {
            AuditLogEvent event = new AuditLogEvent(
                    entry.eventAction(), SOURCE_SYSTEM,
                    entry.correlationId(), entry.actorId(), 1L,
                    entry.eventCategory(), entry.eventAction(),
                    entry.actorType(), entry.actorId(),
                    entry.resourceType(), entry.resourceId(),
                    entry.agencyCode(), entry.sourceIp(),
                    entry.outcome(), entry.outcomeDetail(),
                    metadataJson
            );

            // 파티션 키: agencyCode → agencyCode 없으면 sourceSystem
            String partitionKey = (entry.agencyCode() != null && !entry.agencyCode().isBlank())
                    ? entry.agencyCode() : SOURCE_SYSTEM;

            kafkaTemplate.send(AUDIT_TOPIC, partitionKey, event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("[AuditLogPublisher] Kafka 발행 실패: auditId={} error={}", auditId, ex.getMessage());
                            // DB kafka_published 는 false 유지 → 재처리 스케줄러 대상
                        } else {
                            // DB kafka_published = true 갱신
                            markKafkaPublished(auditId);
                            log.debug("[AuditLogPublisher] Kafka 발행 완료: auditId={} partition={} offset={}",
                                    auditId,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.warn("[AuditLogPublisher] Kafka 발행 요청 실패: auditId={} error={}", auditId, e.getMessage());
        }
    }

    private void markKafkaPublished(String auditId) {
        try {
            jdbcTemplate.update("""
                    UPDATE ido.audit_log
                    SET kafka_published = TRUE, kafka_published_at = NOW()
                    WHERE audit_id = ?
                    """, auditId);
        } catch (Exception e) {
            log.debug("[AuditLogPublisher] kafka_published 갱신 실패 (비치명적): auditId={}", auditId);
        }
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("[AuditLogPublisher] metadata 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }

    // ── 감사 엔트리 레코드 ─────────────────────────────────────────────────

    /**
     * 감사 로그 엔트리 (불변 레코드)
     *
     * <p>Builder 패턴 사용:
     * <pre>{@code
     * AuditEntry.builder()
     *     .eventCategory(AuditLogEvent.CATEGORY_AUTH)
     *     .eventAction("AUTH_COMPLETED")
     *     ...
     *     .build()
     * }</pre>
     */
    public record AuditEntry(
            String eventCategory,
            String eventAction,
            String actorType,
            String actorId,
            String resourceType,
            String resourceId,
            String agencyCode,
            String correlationId,
            String sourceIp,
            String outcome,
            String outcomeDetail,
            Map<String, Object> metadata
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String eventCategory;
            private String eventAction;
            private String actorType  = AuditLogEvent.ACTOR_SYSTEM;
            private String actorId;
            private String resourceType;
            private String resourceId;
            private String agencyCode;
            private String correlationId;
            private String sourceIp;
            private String outcome    = AuditLogEvent.OUTCOME_SUCCESS;
            private String outcomeDetail;
            private Map<String, Object> metadata;

            private Builder() {}

            public Builder eventCategory(String v)  { this.eventCategory  = v; return this; }
            public Builder eventAction(String v)     { this.eventAction    = v; return this; }
            public Builder actorType(String v)       { this.actorType      = v; return this; }
            public Builder actorId(String v)         { this.actorId        = v; return this; }
            public Builder resourceType(String v)    { this.resourceType   = v; return this; }
            public Builder resourceId(String v)      { this.resourceId     = v; return this; }
            public Builder agencyCode(String v)      { this.agencyCode     = v; return this; }
            public Builder correlationId(String v)   { this.correlationId  = v; return this; }
            public Builder sourceIp(String v)        { this.sourceIp       = v; return this; }
            public Builder outcome(String v)         { this.outcome        = v; return this; }
            public Builder outcomeDetail(String v)   { this.outcomeDetail  = v; return this; }
            public Builder metadata(Map<String, Object> v) { this.metadata = v; return this; }

            public AuditEntry build() {
                return new AuditEntry(
                        eventCategory, eventAction,
                        actorType, actorId,
                        resourceType, resourceId,
                        agencyCode, correlationId, sourceIp,
                        outcome, outcomeDetail, metadata
                );
            }
        }
    }

    // ── 미발행 감사 로그 Kafka 재처리 스케줄러 ─────────────────────────────

    /**
     * Kafka 발행 실패한 감사 로그 재처리 (10분 주기)
     * DB에 kafka_published=false 로 남은 레코드를 재발행.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${ido.audit.retry-interval-ms:600000}")
    public void retryKafkaPublish() {
        if (!kafkaPublishEnabled) return;
        try {
            var unpublished = jdbcTemplate.queryForList("""
                    SELECT audit_id, event_category, event_action,
                           actor_type, actor_id, resource_type, resource_id,
                           agency_code, correlation_id, source_ip,
                           outcome, outcome_detail, metadata::text
                    FROM ido.audit_log
                    WHERE kafka_published = FALSE
                    ORDER BY occurred_at ASC
                    LIMIT 200
                    """);

            if (unpublished.isEmpty()) return;
            log.info("[AuditLogPublisher] Kafka 재발행 대상: {}건", unpublished.size());

            for (var row : unpublished) {
                String auditId  = (String) row.get("audit_id");
                String partKey  = row.get("agency_code") != null
                        ? (String) row.get("agency_code") : SOURCE_SYSTEM;

                AuditLogEvent event = new AuditLogEvent(
                        (String) row.get("event_action"), SOURCE_SYSTEM,
                        (String) row.get("correlation_id"), (String) row.get("actor_id"), 1L,
                        (String) row.get("event_category"), (String) row.get("event_action"),
                        (String) row.get("actor_type"), (String) row.get("actor_id"),
                        (String) row.get("resource_type"), (String) row.get("resource_id"),
                        (String) row.get("agency_code"), (String) row.get("source_ip"),
                        (String) row.get("outcome"), (String) row.get("outcome_detail"),
                        (String) row.get("metadata")
                );

                kafkaTemplate.send(AUDIT_TOPIC, partKey, event)
                        .whenComplete((r, ex) -> {
                            if (ex == null) markKafkaPublished(auditId);
                        });
            }
        } catch (Exception e) {
            log.warn("[AuditLogPublisher] 재발행 스케줄 실패: {}", e.getMessage());
        }
    }
}
