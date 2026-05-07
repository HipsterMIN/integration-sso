package kr.go.smes.ido.qim.sp.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.ido.kafka.IdempotentEventStore;
import kr.go.smes.ido.qim.sp.infrastructure.InstMbrIdMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * IdO ← qim.sp.member.events Kafka 컨슈머
 *
 * Q-IM SP 수신 API (MEMBER_REGISTER / MEMBER_WITHDRAW) 처리 결과를
 * IdO 내부 컴포넌트(유관기관 어댑터, 정책 엔진 등)에 전파한다.
 *
 * <p><b>토픽</b>: {@code qim.sp.member.events}
 * <p><b>이벤트 타입</b>:
 * <ul>
 *   <li>{@code QIM_MEMBER_REGISTERED}  — 신규 회원 등록 완료</li>
 *   <li>{@code QIM_MEMBER_TRANSFERRED} — 전환(TRANSFER) 등록 완료</li>
 *   <li>{@code QIM_MEMBER_WITHDRAWN}   — 회원 탈퇴 처리 완료</li>
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

    private static final String CONSUMER_GROUP = "ido-qim-sp-member-consumer";

    // 이벤트 타입 상수
    static final String EVENT_QIM_MEMBER_REGISTERED  = "QIM_MEMBER_REGISTERED";
    static final String EVENT_QIM_MEMBER_TRANSFERRED = "QIM_MEMBER_TRANSFERRED";
    static final String EVENT_QIM_MEMBER_WITHDRAWN   = "QIM_MEMBER_WITHDRAWN";

    private final IdempotentEventStore idempotentEventStore;
    private final QimSpMemberEventHandler eventHandler;
    private final ObjectMapper objectMapper;

    /**
     * qim.sp.member.events 구독
     *
     * <p>메시지 형식 (Outbox 발행 payload):
     * <pre>
     * {
     *   "eventId":        "UUID",
     *   "eventType":      "QIM_MEMBER_REGISTERED | QIM_MEMBER_TRANSFERRED | QIM_MEMBER_WITHDRAWN",
     *   "instMbrId":      "UUID",
     *   "mbrUuid":        "Q-IM 발행 mbrUuid",
     *   "regMode":        "NEW | TRANSFER",        // REGISTER 이벤트만
     *   "memberType":     "PERSONAL | CORPORATE",  // REGISTER 이벤트만
     *   "identifierHash": "SHA-256(CI)",           // REGISTER 이벤트만
     *   "withdrawalReason": "...",                  // WITHDRAW 이벤트만
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
            case EVENT_QIM_MEMBER_REGISTERED  -> {
                eventHandler.onMemberRegistered(payload, correlationId);
                yield "OK_REGISTERED";
            }
            case EVENT_QIM_MEMBER_TRANSFERRED -> {
                eventHandler.onMemberTransferred(payload, correlationId);
                yield "OK_TRANSFERRED";
            }
            case EVENT_QIM_MEMBER_WITHDRAWN   -> {
                eventHandler.onMemberWithdrawn(payload, correlationId);
                yield "OK_WITHDRAWN";
            }
            default -> {
                log.warn("[QimSpMemberEventConsumer] 알 수 없는 eventType={} — SKIP", eventType);
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
