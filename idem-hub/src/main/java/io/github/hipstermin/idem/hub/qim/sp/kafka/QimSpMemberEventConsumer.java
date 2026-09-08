package io.github.hipstermin.idem.hub.qim.sp.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.hub.kafka.IdempotentEventStore;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * IdO ← qim.sp.member.events Kafka 컨슈머 (SP 회원 등록/전환/탈퇴 이벤트)
 *
 * Q-IM이 SP 회원 등록 · 전환 · 탈퇴 처리 후 qim.sp.member.events로 발행한 이벤트를
 * 소비하여 IdO 내부 컴포넌트(유관기관 어댑터, 정책 엔진 등)에 전파한다.
 *
 * <p><b>토픽</b>: {@code qim.sp.member.events} (설정 키: {@code ido.kafka.topic-qim-sp-member-events})
 * <p><b>이벤트 타입 (QIM-OUTBOX-SPEC-001 기준)</b>:
 * <ul>
 *   <li>{@code BIZ_MEMBER_CONVERTED}      — 기업회원 전환 (구 QIM_MEMBER_TRANSFERRED + CORPORATE)</li>
 *   <li>{@code BIZ_MEMBER_REGISTERED}     — 기업회원 신규 등록 (구 QIM_MEMBER_REGISTERED + CORPORATE)</li>
 *   <li>{@code PERSONAL_MEMBER_CONVERTED} — 개인회원 전환 (구 QIM_MEMBER_TRANSFERRED + PERSONAL)</li>
 *   <li>{@code PERSONAL_MEMBER_REGISTERED}— 개인회원 신규 등록 (구 QIM_MEMBER_REGISTERED + PERSONAL)</li>
 *   <li>{@code MEMBER_WITHDRAWN}          — 회원 탈퇴 (구 QIM_MEMBER_WITHDRAWN)</li>
 * </ul>
 *
 * <p><b>처리 원칙</b>:
 * <ol>
 *   <li>멱등 처리: 동일 eventId 중복 수신 시 스킵</li>
 *   <li>비즈니스 실패는 예외로 전파 → DefaultErrorHandler(지수 백오프) 위임</li>
 *   <li>MANUAL_IMMEDIATE ACK: 처리 완료 후 명시적 커밋</li>
 * </ol>
 *
 * <p><b>아키텍처 참조</b>:
 * <a href="docs/qim-ido-integration-architecture.md">§10 EDA 기반 내부 전파 설계</a>
 *
 * @see QimSpMemberEventHandler
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QimSpMemberEventConsumer {

    // QIM-OUTBOX-SPEC-001: qim.sp.member.events SP 회원 이벤트 전용 그룹
    private static final String CONSUMER_GROUP = "ido-qim-sp-member-consumer";

    // ── 이벤트 타입 상수 (QIM-OUTBOX-SPEC-001 기준) ─────────────────────────
    static final String EVENT_BIZ_MEMBER_CONVERTED       = "BIZ_MEMBER_CONVERTED";
    static final String EVENT_BIZ_MEMBER_REGISTERED      = "BIZ_MEMBER_REGISTERED";
    static final String EVENT_PERSONAL_MEMBER_CONVERTED  = "PERSONAL_MEMBER_CONVERTED";
    static final String EVENT_PERSONAL_MEMBER_REGISTERED = "PERSONAL_MEMBER_REGISTERED";
    static final String EVENT_MEMBER_WITHDRAWN           = "MEMBER_WITHDRAWN";

    private final IdempotentEventStore idempotentEventStore;
    private final QimSpMemberEventHandler eventHandler;
    private final ObjectMapper objectMapper;

    /**
     * qim.sp.member.events 구독 (SP 회원 등록/전환/탈퇴)
     *
     * <p>메시지 형식 (qim_outbox payload 기준 QIM-OUTBOX-SPEC-001 §5):
     * <pre>
     * {
     *   "eventId":        "UUID",
     *   "eventType":      "BIZ_MEMBER_CONVERTED | BIZ_MEMBER_REGISTERED
     *                      | PERSONAL_MEMBER_CONVERTED | PERSONAL_MEMBER_REGISTERED
     *                      | MEMBER_WITHDRAWN",
     *   "instMbrId":      "UUID",
     *   "mbrUuid":        "Q-IM 발행 mbrUuid",
     *   "memberType":     "BIZ | PERSONAL",      // 등록/전환 이벤트만
     *   "identifierHash": "SHA-256(CI)",          // 등록/전환 이벤트만
     *   "withdrawalReason": "...",                 // MEMBER_WITHDRAWN만
     *   "correlationId":  "UUID"
     * }
     * </pre>
     */
    @KafkaListener(
            topics           = "${ido.kafka.topic-qim-sp-member-events:qim.sp.member.events}",
            groupId          = "${ido.kafka.consumer-group-qim-sp-member:ido-qim-sp-member-consumer}",
            containerFactory = "qimSpMemberListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {

        String rawValue = record.value();

        if (rawValue == null || rawValue.isBlank()) {
            log.warn("[QimSpMemberEventConsumer] null/빈 메시지 수신 partition={} offset={} — ACK 처리",
                    record.partition(), record.offset());
            ack.acknowledge();
            return;
        }

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(rawValue, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("[QimSpMemberEventConsumer] 페이로드 역직렬화 실패 partition={} offset={} raw={}",
                    record.partition(), record.offset(),
                    rawValue.length() > 200 ? rawValue.substring(0, 200) + "..." : rawValue,
                    e);
            // 역직렬화 불가 메시지는 DLQ 없이 ACK (poison pill 방지)
            ack.acknowledge();
            return;
        }

        String eventId   = extractString(payload, "eventId");
        String eventType = extractString(payload, "eventType");
        String instMbrId = extractString(payload, "instMbrId");
        String correlationId = extractString(payload, "correlationId");

        log.info("[QimSpMemberEventConsumer] 이벤트 수신 eventId={} type={} instMbrId={} correlationId={}",
                eventId, eventType, instMbrId, correlationId);

        try {
            // ① 멱등 처리: 이미 처리된 eventId 스킵
            if (eventId != null && idempotentEventStore.isAlreadyProcessed(eventId, CONSUMER_GROUP)) {
                log.debug("[QimSpMemberEventConsumer] 중복 이벤트 스킵 eventId={}", eventId);
                ack.acknowledge();
                return;
            }

            // ② 이벤트 타입별 처리 위임
            String resultCode = dispatch(eventType, payload, correlationId);

            // ③ 처리 완료 기록
            if (eventId != null) {
                idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, eventType, resultCode);
            }

            log.info("[QimSpMemberEventConsumer] 처리 완료 eventId={} type={} instMbrId={} result={}",
                    eventId, eventType, instMbrId, resultCode);

        } catch (Exception e) {
            log.error("[QimSpMemberEventConsumer] 처리 실패 eventId={} type={} instMbrId={}",
                    eventId, eventType, instMbrId, e);
            // DefaultErrorHandler 지수 백오프 재시도 위임
            throw e;
        } finally {
            ack.acknowledge();
        }
    }

    // ── 이벤트 타입 디스패치 ────────────────────────────────────────────────

    private String dispatch(String eventType, Map<String, Object> payload, String correlationId) {
        if (eventType == null) {
            log.warn("[QimSpMemberEventConsumer] eventType 없음 — SKIP");
            return "SKIP_NO_TYPE";
        }
        return switch (eventType) {
            // ── 기업회원 ──────────────────────────────────────────────────────────
            case EVENT_BIZ_MEMBER_CONVERTED -> {
                eventHandler.onBizMemberConverted(payload, correlationId);
                yield "OK_BIZ_CONVERTED";
            }
            case EVENT_BIZ_MEMBER_REGISTERED -> {
                eventHandler.onBizMemberRegistered(payload, correlationId);
                yield "OK_BIZ_REGISTERED";
            }
            // ── 개인회원 ──────────────────────────────────────────────────────────
            case EVENT_PERSONAL_MEMBER_CONVERTED -> {
                eventHandler.onPersonalMemberConverted(payload, correlationId);
                yield "OK_PERSONAL_CONVERTED";
            }
            case EVENT_PERSONAL_MEMBER_REGISTERED -> {
                eventHandler.onPersonalMemberRegistered(payload, correlationId);
                yield "OK_PERSONAL_REGISTERED";
            }
            // ── 탈퇴 ──────────────────────────────────────────────────────────────
            case EVENT_MEMBER_WITHDRAWN -> {
                eventHandler.onMemberWithdrawn(payload, correlationId);
                yield "OK_WITHDRAWN";
            }
            default -> {
                // 미지원 타입은 WARN 로그 + ACK 처리 (재시도/DLQ 진입 방지)
                log.warn("[QimSpMemberEventConsumer] 미지원 eventType={} — SKIP", eventType);
                yield "SKIP_UNKNOWN_TYPE";
            }
        };
    }

    // ── 유틸 ────────────────────────────────────────────────────────────────

    private String extractString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
