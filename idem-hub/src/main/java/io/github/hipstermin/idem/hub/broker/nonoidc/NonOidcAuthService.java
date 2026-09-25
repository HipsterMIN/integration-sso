package io.github.hipstermin.idem.hub.broker.nonoidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuthEvent;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.broker.BrokerAuditLogService;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비OIDC 인증 AuthResult 생성 서비스 (문서 §9)
 *
 * <p>PASS / 금융인증서 / GPKI / 공동인증서 등 비OIDC 인증 수단에 대해
 * IdO가 직접 AuthResult를 생성하고 {@code idem.gate.auth.events} Kafka 토픽에 발행.
 *
 * <p>처리 흐름 (Strategy B — 문서 §8.3):
 * <ol>
 *   <li>외부 IdP 응답을 IdpBrokerService가 정규화 → {@code IdOAuthInput}</li>
 *   <li>이 서비스가 AuthResult 생성 → {@code ido.auth_result} DB 저장</li>
 *   <li>Outbox 이벤트 저장 → Kafka {@code idem.gate.auth.events} 발행</li>
 *   <li>기존 {@code QsignAuthEventConsumer} 변경 없이 소비</li>
 * </ol>
 *
 * <p>지원 인증 수단:
 * <ul>
 *   <li>PASS — 통신 3사 본인인증 (L2)</li>
 *   <li>FINANCIAL_CERT — 금융인증서 (L3)</li>
 *   <li>GPKI — 정부 공개키 인증서 (L3)</li>
 *   <li>JOINT_CERT — 공동인증서 (L3)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NonOidcAuthService {

    private static final String SOURCE_SYSTEM = "ido-nonoidc";

    private final JdbcTemplate               jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper               objectMapper;
    private final BrokerAuditLogService      brokerAuditLogService;

    @Value("${idem.hub.kafka.topic-auth-events:idem.gate.auth.events}")
    private String authEventsTopic;

    // ══════════════════════════════════════════════════════════════════════
    // 공개 API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 비OIDC 인증 AuthResult 생성 + Kafka 이벤트 발행
     *
     * @param command 인증 처리 명령 DTO
     * @return 생성된 AuthResult ID
     */
    @Transactional
    public String processAuth(NonOidcAuthCommand command) {
        String correlationId  = command.getCorrelationId();
        String providerCode   = command.getProviderCode();
        String identifierHash = computeIdentifierHash(command.getRawIdentifier(), correlationId);
        String authResultId   = UuidV7.generate();
        String authLevel      = resolveAuthLevel(providerCode);

        log.info("[NonOidcAuthService] 인증 처리: providerCode={} authLevel={} correlationId={}",
                providerCode, authLevel, correlationId);

        // 1. AuthResult DB 저장 (V10: auth_method 추가)
        String authMethod = io.github.hipstermin.idem.common.domain.AuthResult.resolveAuthMethod(providerCode);
        saveAuthResult(authResultId, correlationId, authLevel, providerCode,
                command.getProviderTxId(), identifierHash, authMethod);

        // 2. Outbox 이벤트 저장 + Kafka 발행
        saveAndPublishEvent(authResultId, correlationId, authLevel, providerCode, identifierHash);

        // 3. broker_audit_log COMPLETE 기록 (P1)
        String authMethodForType = io.github.hipstermin.idem.common.domain.AuthResult.resolveAuthMethod(providerCode);
        String providerType = authMethodForType.startsWith("STANDARD_OIDC") ? "STANDARD_OIDC"
                : authMethodForType.startsWith("SEMI_STANDARD_OIDC") ? "SEMI_STANDARD_OIDC"
                : "NON_STANDARD";
        brokerAuditLogService.recordComplete(
                correlationId, providerCode, providerType,
                command.getProviderTxId(), identifierHash, authLevel, "nonoidc", null
        );

        log.info("[NonOidcAuthService] 처리 완료: authResultId={} correlationId={}",
                authResultId, correlationId);
        return authResultId;
    }

    /**
     * 잠금 상태 기록 (연속 실패 시 ido.auth_lock 업데이트)
     *
     * @param identifierHash 식별자 해시
     * @param providerCode   인증 수단 코드
     * @param correlationId  흐름 추적 ID
     */
    @Transactional
    public void recordFailure(String identifierHash, String providerCode, String correlationId) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ido.auth_lock
                        (lock_id, identifier_hash, provider_code, failure_count,
                         first_failure_at, last_failure_at, locked_until)
                    VALUES (?, ?, ?, 1, NOW(), NOW(), NULL)
                    ON CONFLICT (identifier_hash, provider_code) DO UPDATE
                        SET failure_count   = ido.auth_lock.failure_count + 1,
                            last_failure_at = NOW(),
                            locked_until    = CASE
                                WHEN ido.auth_lock.failure_count + 1 >= 5
                                THEN NOW() + INTERVAL '30 minutes'
                                ELSE NULL
                            END
                    """,
                    UuidV7.generate(), identifierHash, providerCode
            );

            // 잠금 여부 확인 후 LOCKED 이벤트 발행
            Boolean locked = jdbcTemplate.queryForObject("""
                    SELECT locked_until IS NOT NULL AND locked_until > NOW()
                    FROM ido.auth_lock
                    WHERE identifier_hash = ? AND provider_code = ?
                    """, Boolean.class, identifierHash, providerCode);

            if (Boolean.TRUE.equals(locked)) {
                publishLockedEvent(identifierHash, providerCode, correlationId);
            }
        } catch (Exception e) {
            log.warn("[NonOidcAuthService] auth_lock 기록 실패 (무시): correlationId={}", correlationId, e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 내부 처리 메서드
    // ══════════════════════════════════════════════════════════════════════

    private void saveAuthResult(String authResultId, String correlationId,
                                 String authLevel, String providerCode,
                                 String providerTxId, String identifierHash,
                                 String authMethod) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ido.auth_result
                        (auth_result_id, correlation_id, auth_level, provider_code,
                         provider_tx_id, identifier_hash, verification_result,
                         source_system, auth_method,
                         authenticated_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'SUCCESS', ?, ?, NOW(), NOW())
                    ON CONFLICT (auth_result_id) DO NOTHING
                    """,
                    authResultId, correlationId, authLevel, providerCode,
                    providerTxId, identifierHash, SOURCE_SYSTEM,
                    authMethod  // V10: 비OIDC 인증 수단은 issued_at/expires_at/raw_id_token = NULL
            );
            log.debug("[NonOidcAuthService] auth_result 저장: authResultId={} authMethod={}",
                    authResultId, authMethod);
        } catch (Exception e) {
            log.error("[NonOidcAuthService] auth_result 저장 실패: authResultId={}", authResultId, e);
            throw new PlatformException(PlatformErrorCode.QS_AUTH_FAILED, correlationId,
                    "비OIDC AuthResult 저장 실패: " + e.getMessage());
        }
    }

    private void saveAndPublishEvent(String authResultId, String correlationId,
                                      String authLevel, String providerCode,
                                      String identifierHash) {
        String eventId = UuidV7.generate();
        try {
            String payload = buildEventPayload(eventId, authResultId, correlationId,
                    authLevel, providerCode, identifierHash);

            jdbcTemplate.update("""
                    INSERT INTO ido.outbox
                        (event_id, event_type, partition_key, aggregate_id,
                         payload, topic, status, created_at)
                    VALUES (?, 'AUTH_COMPLETED', ?, ?, ?::jsonb, ?, 'PENDING', NOW())
                    """,
                    eventId, identifierHash, authResultId, payload, authEventsTopic
            );

            // 즉시 Kafka 발행 (Outbox relay 보완)
            AuthEvent event = new AuthEvent(
                    AuthEvent.TYPE_AUTH_COMPLETED,
                    SOURCE_SYSTEM,
                    correlationId,
                    identifierHash,
                    1L,
                    authResultId,
                    AuthResult.AuthLevel.valueOf(authLevel),
                    providerCode,
                    null,
                    AuthResult.VerificationResult.SUCCESS
            );
            kafkaTemplate.send(authEventsTopic, identifierHash, event);

        } catch (Exception e) {
            log.error("[NonOidcAuthService] outbox/kafka 처리 실패: correlationId={}", correlationId, e);
            throw new PlatformException(PlatformErrorCode.QS_AUTH_FAILED, correlationId,
                    "이벤트 처리 실패: " + e.getMessage());
        }
    }

    private void publishLockedEvent(String identifierHash, String providerCode, String correlationId) {
        try {
            AuthEvent event = new AuthEvent(
                    AuthEvent.TYPE_AUTH_LOCKED,
                    SOURCE_SYSTEM,
                    correlationId,
                    identifierHash,
                    1L,
                    null,
                    null,
                    providerCode,
                    null,
                    AuthResult.VerificationResult.FAIL
            );
            kafkaTemplate.send(authEventsTopic, identifierHash, event);
            log.warn("[NonOidcAuthService] AUTH_LOCKED 이벤트 발행: correlationId={} provider={}",
                    correlationId, providerCode);
        } catch (Exception e) {
            log.warn("[NonOidcAuthService] AUTH_LOCKED 이벤트 발행 실패 (무시): {}", e.getMessage());
        }
    }

    /**
     * 인증 수단 → AuthLevel 매핑 (문서 §4)
     */
    private String resolveAuthLevel(String providerCode) {
        if (providerCode == null) return "L1";
        return switch (providerCode.toUpperCase()) {
            case "PASS"           -> "L2";
            case "FINANCIAL_CERT",
                 "GPKI",
                 "JOINT_CERT"    -> "L3";
            default               -> "L1";
        };
    }

    /**
     * rawIdentifier → identifierHash: SHA-256(rawIdentifier) → hex
     */
    private String computeIdentifierHash(String rawIdentifier, String correlationId) {
        if (rawIdentifier == null || rawIdentifier.isBlank()) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "rawIdentifier 없음 — identifierHash 생성 불가");
        }
        return CryptoProviders.current().sha256Hex(rawIdentifier);
    }

    private String buildEventPayload(String eventId, String authResultId, String correlationId,
                                      String authLevel, String providerCode, String identifierHash) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "eventId",        eventId,
                    "eventType",      AuthEvent.TYPE_AUTH_COMPLETED,
                    "sourceSystem",   SOURCE_SYSTEM,
                    "correlationId",  correlationId,
                    "authResultId",   authResultId,
                    "authLevel",      authLevel,
                    "providerCode",   providerCode,
                    "identifierHash", identifierHash,
                    "occurredAt",     Instant.now().toString()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("이벤트 페이로드 직렬화 실패", e);
        }
    }
}
