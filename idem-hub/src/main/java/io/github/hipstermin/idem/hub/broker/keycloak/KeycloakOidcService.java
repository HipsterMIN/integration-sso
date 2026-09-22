package io.github.hipstermin.idem.hub.broker.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuthEvent;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.auth.dto.im.QimMemberInfo;
import io.github.hipstermin.idem.hub.auth.dto.im.QimRegisterResponse;
import io.github.hipstermin.idem.hub.broker.BrokerAuditLogService;
import io.github.hipstermin.idem.hub.broker.keycloak.dto.KeycloakJwtClaims;
import io.github.hipstermin.idem.hub.broker.keycloak.dto.KeycloakTokenResponse;
import io.github.hipstermin.idem.hub.broker.state.IdoOidcStateEntry;
import io.github.hipstermin.idem.hub.broker.state.IdoOidcStateStore;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Keycloak OIDC 콜백 처리 서비스 (문서 §7, §8)
 *
 * <p>Keycloak → ido GET /api/v1/broker/callback 콜백 수신 후 전 과정 처리:
 * <ol>
 *   <li>state 검증 (Redis 1회 소비 — CSRF 방지)</li>
 *   <li>authorization code → token 교환 (Keycloak Token Endpoint)</li>
 *   <li>id_token JWKS 서명 검증 + nonce 검증 (replay attack 방지)</li>
 *   <li>identifierHash 생성 (SHA-256(sub)) — 감사 로그/DB 추적용으로만 사용</li>
 *   <li>Q-IM에서 실제 qimUserId 조회/등록 (SSO 핵심 — agencySubjectId 정확성 보장)</li>
 *   <li>AuthResult 생성 → ido.auth_result 저장 (Keycloak 모드 Strategy B)</li>
 *   <li>Outbox 이벤트 저장 → Kafka qsign.auth.events 발행</li>
 *   <li>FE 세션 생성 → feSessionId 쿠키 발급 준비</li>
 * </ol>
 *
 * <p>Strategy B (문서 §8.3):
 * Keycloak 도입 후 q-sign이 더 이상 콜백을 수신하지 않으므로,
 * IdO가 직접 AuthResult를 생성하고 {@code qsign.auth.events} Kafka 토픽에 발행.
 * 기존 Q-IM, Handoff 처리 등 하위 컨슈머(QsignAuthEventConsumer 등)는 변경 없음.
 *
 * <p><b>SSO 식별자 전략 (v2.4.0 수정)</b>:
 * Keycloak 소셜 로그인 콜백에서 CI를 직접 받을 수 없으므로, Q-IM의
 * {@code /api/v1/internal/users/find-or-register-by-sub} API를 통해
 * Keycloak sub → qimUserId를 조회/등록한다.
 * identifierHash(SHA-256(sub))는 감사 로그 및 DB 추적 목적으로만 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakOidcService {

    private static final String SOURCE_SYSTEM = "ido-keycloak";

    private final IdoOidcStateStore          stateStore;
    private final KeycloakJwksVerifier       jwksVerifier;
    private final KeycloakProperties         keycloakProperties;
    private final FeSessionService           feSessionService;
    private final QimClient                  qimClient;
    private final RestTemplate               restTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JdbcTemplate               jdbcTemplate;
    private final ObjectMapper               objectMapper;
    private final BrokerAuditLogService      brokerAuditLogService;

    // P1: ido.keycloak.auth-events-topic(구 키) → ido.kafka.topic-auth-events 로 통일
    @Value("${ido.kafka.topic-auth-events:qsign.auth.events}")
    private String authEventsTopic;

    // ══════════════════════════════════════════════════════════════════════
    // 공개 API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Keycloak Authorization Code Flow 콜백 전 과정 처리
     *
     * @param code  Keycloak이 redirect_uri로 전달한 authorization_code
     * @param state Keycloak이 전달한 state (CSRF 검증용)
     * @return {@link CallbackResult} — FE 세션 + redirect URL 포함
     */
    @Transactional
    public CallbackResult handleCallback(String code, String state) {
        // ── 1. state 검증 (1회 소비) ────────────────────────────────────
        IdoOidcStateEntry stateEntry = stateStore.consumeAndValidate(state)
                .orElseThrow(() -> {
                    log.warn("[KeycloakOidcService] state 검증 실패 (만료 또는 CSRF): state(prefix)={}",
                            state.length() >= 8 ? state.substring(0, 8) : state);
                    return new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, "unknown",
                            "state 검증 실패 — CSRF 또는 만료");
                });

        String correlationId = stateEntry.getCorrelationId();
        log.info("[KeycloakOidcService] 콜백 수신: correlationId={} provider={}",
                correlationId, stateEntry.getProvider());

        // ── 2. Authorization Code → Token 교환 ──────────────────────────
        KeycloakTokenResponse tokenResponse = exchangeCodeForToken(code, correlationId);

        // ── 3. id_token 서명 검증 + 클레임 파싱 ─────────────────────────
        KeycloakJwtClaims claims = jwksVerifier.verifyAndParse(
                tokenResponse.getIdToken(), correlationId);

        // ── 4. nonce 검증 (replay attack 방지) ───────────────────────────
        validateNonce(claims.getNonce(), stateEntry.getNonce(), correlationId);

        // ── 5. audience 검증 ─────────────────────────────────────────────
        validateAudience(claims.getAudienceAsString(), correlationId);

        // ── 6. identifierHash 생성 (SHA-256(sub)) — 감사 로그/DB 추적 전용 ──
        String identifierHash = computeIdentifierHash(claims.getSubject(), correlationId);

        // ── 7. providerCode 결정 ─────────────────────────────────────────
        String providerCode = resolveProviderCode(claims, stateEntry);

        // ── 8. Q-IM에서 실제 qimUserId 조회/등록 (SSO 핵심) ──────────────
        // Keycloak 소셜 로그인 경로에서는 CI가 없다. 대신 Q-IM의 소셜 계정 연동 API
        // (sub 기반 find-or-register)를 통해 실제 qimUserId를 확보한다.
        // qimUserId가 있어야 PolicyEngine이 Q-IM DI를 정확히 조회하고
        // agencySubjectId를 올바르게 계산할 수 있다 (SSO 기관 간 동일 사용자 식별).
        String qimUserId = resolveQimUserIdFromSub(
                claims.getSubject(), identifierHash, providerCode, correlationId);

        // ── 9. AuthResult 생성 + DB 저장 (Strategy B) ────────────────────
        String authResultId = UuidV7.generate();
        String authLevel    = keycloakProperties.resolveAuthLevel(claims.getAcr());
        String authMethod   = io.github.hipstermin.idem.common.domain.AuthResult.resolveAuthMethod(providerCode);
        Instant issuedAt    = claims.getIssuedAt() > 0 ? Instant.ofEpochSecond(claims.getIssuedAt()) : null;
        Instant expiresAt   = claims.getExpiresAt() > 0 ? Instant.ofEpochSecond(claims.getExpiresAt()) : null;

        saveAuthResult(authResultId, correlationId, authLevel, providerCode,
                identifierHash, claims.getSubject(),
                authMethod, issuedAt, expiresAt, tokenResponse.getIdToken());

        // ── 10. Outbox 이벤트 저장 → Kafka 발행 ──────────────────────────
        saveOutboxEvent(authResultId, correlationId, authLevel, providerCode,
                qimUserId, identifierHash);

        // ── 11. FE 세션 생성 (실제 qimUserId 사용) ───────────────────────
        log.debug("[KeycloakOidcService] FE 세션 생성: qimUserId(prefix)={} authResultId={}",
                qimUserId.length() >= 8 ? qimUserId.substring(0, 8) : qimUserId,
                authResultId);
        FeSession feSession = feSessionService.create(
                qimUserId,    // 실제 Q-IM 사용자 ID — SSO Handoff 정확성 보장
                authResultId,
                authLevel,
                stateEntry.getReturnUrl()
        );

        // ── 12. OIDC 세션 로그 기록 ──────────────────────────────────────
        saveOidcSessionLog(correlationId, providerCode, claims.getSubject(),
                identifierHash, authResultId);

        // ── 13. broker_audit_log COMPLETE 기록 (P1) ──────────────────────
        brokerAuditLogService.recordComplete(
                correlationId, providerCode, "STANDARD_OIDC",
                claims.getSubject(), qimUserId, authLevel, "keycloak", null
        );

        log.info("[KeycloakOidcService] 인증 완료: correlationId={} authResultId={} authLevel={} qimUserId(prefix)={}",
                correlationId, authResultId, authLevel,
                qimUserId.length() >= 8 ? qimUserId.substring(0, 8) : qimUserId);

        return CallbackResult.builder()
                .feSession(feSession)
                .authResultId(authResultId)
                .authLevel(authLevel)
                .providerCode(providerCode)
                .correlationId(correlationId)
                .returnUrl(stateEntry.getReturnUrl())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 내부 처리 메서드
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Keycloak Token Endpoint에 authorization code 교환 요청
     * POST {keycloak}/realms/{realm}/protocol/openid-connect/token
     */
    private KeycloakTokenResponse exchangeCodeForToken(String code, String correlationId) {
        String tokenEndpoint = keycloakProperties.tokenEndpoint();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type",    "authorization_code");
        params.add("code",          code);
        params.add("redirect_uri",  keycloakProperties.getRedirectUri());
        params.add("client_id",     keycloakProperties.getClientId());
        params.add("client_secret", keycloakProperties.getClientSecret());

        try {
            ResponseEntity<KeycloakTokenResponse> resp = restTemplate.exchange(
                    tokenEndpoint, HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    KeycloakTokenResponse.class
            );

            KeycloakTokenResponse tokenResp = resp.getBody();
            if (tokenResp == null || tokenResp.getIdToken() == null) {
                throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                        "Keycloak token 응답에 id_token 없음");
            }

            log.debug("[KeycloakOidcService] token 교환 성공: correlationId={}", correlationId);
            return tokenResp;

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[KeycloakOidcService] Keycloak token 교환 실패: correlationId={}", correlationId, e);
            throw new PlatformException(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE, correlationId,
                    "Keycloak Token Endpoint 오류: " + e.getMessage());
        }
    }

    /**
     * nonce 검증 — id_token의 nonce와 Redis에 저장된 nonce 비교
     */
    private void validateNonce(String tokenNonce, String expectedNonce, String correlationId) {
        if (expectedNonce == null || !expectedNonce.equals(tokenNonce)) {
            log.warn("[KeycloakOidcService] nonce 불일치 — replay attack 가능: correlationId={}", correlationId);
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "nonce 검증 실패 — replay attack 방어");
        }
    }

    /**
     * audience 검증 — id_token의 aud가 ido-client인지 확인
     */
    private void validateAudience(String audience, String correlationId) {
        String expectedClientId = keycloakProperties.getClientId();
        if (audience == null || !audience.contains(expectedClientId)) {
            log.warn("[KeycloakOidcService] audience 불일치: expected={} actual={} correlationId={}",
                    expectedClientId, audience, correlationId);
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "id_token audience 불일치");
        }
    }

    /**
     * Keycloak sub → Q-IM qimUserId 조회/등록 (SSO 핵심 메서드)
     *
     * <p>소셜 로그인 경로에서 CI는 없으나, Q-IM은 소셜 계정(Keycloak sub)을 키로
     * 사용자를 등록/조회할 수 있다. 이를 통해 실제 qimUserId를 확보하여
     * Handoff 발급 시 PolicyEngine이 정확한 agencySubjectId를 계산하도록 한다.
     *
     * <p><b>흐름</b>:
     * <ol>
     *   <li>Q-IM {@code findBySocialSub(sub, providerCode)} 조회</li>
     *   <li>기존 사용자 → qimUserId 반환</li>
     *   <li>신규 사용자 → {@code registerSocialUser(sub, providerCode, identifierHash)} 등록 후 qimUserId 반환</li>
     *   <li>Q-IM 통신 실패 → identifierHash 폴백 + ERROR 로그 (SSO 기능 저하, 인증 플로우 중단하지 않음)</li>
     * </ol>
     *
     * @param sub           Keycloak id_token sub 클레임
     * @param identifierHash SHA-256(sub) — 폴백 식별자
     * @param providerCode  소셜 제공자 코드 (KAKAO_OIDC 등)
     * @param correlationId 요청 추적 ID
     * @return 실제 qimUserId (Q-IM 통신 실패 시 identifierHash 폴백)
     */
    private String resolveQimUserIdFromSub(String sub, String identifierHash,
                                            String providerCode, String correlationId) {
        try {
            // Q-IM 소셜 계정 조회 (sub + providerCode 기반)
            Optional<QimMemberInfo> existing = qimClient.findBySocialSub(sub, providerCode, correlationId);

            if (existing.isPresent()) {
                String qimUserId = existing.get().getQimUserId();
                log.info("[KeycloakOidcService] Q-IM 소셜 기존 사용자: qimUserId(prefix)={} correlationId={}",
                        qimUserId.length() >= 8 ? qimUserId.substring(0, 8) : qimUserId, correlationId);
                return qimUserId;
            }

            // 신규 소셜 사용자 → Q-IM 등록
            log.info("[KeycloakOidcService] Q-IM 소셜 신규 등록: providerCode={} correlationId={}",
                    providerCode, correlationId);
            QimRegisterResponse registered = qimClient.registerSocialUser(
                    sub, providerCode, identifierHash, correlationId);
            String newQimUserId = registered.getQimUserId();
            log.info("[KeycloakOidcService] Q-IM 소셜 등록 완료: qimUserId(prefix)={} isNew={} correlationId={}",
                    newQimUserId.length() >= 8 ? newQimUserId.substring(0, 8) : newQimUserId,
                    registered.getIsNew(), correlationId);
            return newQimUserId;

        } catch (Exception e) {
            // Q-IM 통신 실패 → identifierHash 폴백 (인증 플로우는 계속)
            // 이 경우 Handoff 발급 시 agencySubjectId가 HMAC fallback으로 계산됨
            // 운영 환경에서는 반드시 Q-IM 연동 상태를 모니터링해야 함
            log.error("[KeycloakOidcService][SSO-DEGRADED] Q-IM 소셜 계정 조회/등록 실패 " +
                      "— identifierHash 폴백 사용 (SSO agencySubjectId 정확성 저하): " +
                      "correlationId={} cause={}", correlationId, e.getMessage());
            return identifierHash;
        }
    }

    /**
     * identifierHash 생성: SHA-256(sub) → hex encoding
     */
    private String computeIdentifierHash(String sub, String correlationId) {
        if (sub == null || sub.isBlank()) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "id_token sub 클레임 없음");
        }
        return CryptoProviders.current().sha256Hex(sub);
    }

    /**
     * providerCode 결정: identity_provider 클레임 → KAKAO_OIDC 등
     * identity_provider 클레임이 없으면 state의 provider 필드 사용
     */
    private String resolveProviderCode(KeycloakJwtClaims claims, IdoOidcStateEntry stateEntry) {
        if (claims.getIdentityProvider() != null && !claims.getIdentityProvider().isBlank()) {
            return keycloakProperties.resolveProviderCode(claims.getIdentityProvider());
        }
        // fallback: state에 저장된 provider (kakao → KAKAO_OIDC)
        String provider = stateEntry.getProvider();
        return provider != null ? provider.toUpperCase() + "_OIDC" : "UNKNOWN_OIDC";
    }

    /**
     * AuthResult DB 저장 (Strategy B — IdO가 직접 생성)
     * 테이블: ido.auth_result (V3 생성, V10에서 auth_method/issued_at/expires_at/raw_id_token 추가)
     */
    private void saveAuthResult(String authResultId, String correlationId,
                                 String authLevel, String providerCode,
                                 String identifierHash, String sub,
                                 String authMethod, Instant issuedAt, Instant expiresAt,
                                 String rawIdToken) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ido.auth_result
                        (auth_result_id, correlation_id, auth_level, provider_code,
                         provider_tx_id, identifier_hash, verification_result, source_system,
                         auth_method, issued_at, expires_at, raw_id_token,
                         authenticated_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'SUCCESS', ?, ?, ?, ?, ?, NOW(), NOW())
                    ON CONFLICT (auth_result_id) DO NOTHING
                    """,
                    authResultId, correlationId, authLevel, providerCode,
                    sub,            // provider_tx_id = Keycloak sub
                    identifierHash,
                    SOURCE_SYSTEM,
                    authMethod,     // V10: auth_method (§24.4.1)
                    issuedAt != null ? java.sql.Timestamp.from(issuedAt) : null,
                    expiresAt != null ? java.sql.Timestamp.from(expiresAt) : null,
                    rawIdToken      // V10: raw_id_token (감사 목적, 운영 시 암호화 고려)
            );
            log.debug("[KeycloakOidcService] auth_result 저장: authResultId={} authMethod={}",
                    authResultId, authMethod);
        } catch (Exception e) {
            log.error("[KeycloakOidcService] auth_result 저장 실패: authResultId={}", authResultId, e);
            throw new PlatformException(PlatformErrorCode.QS_AUTH_FAILED, correlationId,
                    "AuthResult 저장 실패: " + e.getMessage());
        }
    }

    /**
     * Outbox 이벤트 저장 — Relay가 Kafka qsign.auth.events로 발행
     * 기존 QsignAuthEventConsumer가 변경 없이 소비 가능
     */
    private void saveOutboxEvent(String authResultId, String correlationId,
                                  String authLevel, String providerCode,
                                  String qimUserId, String identifierHash) {
        try {
            String eventId  = UuidV7.generate();
            String payload  = buildAuthEventPayload(eventId, authResultId, correlationId,
                    authLevel, providerCode, qimUserId, identifierHash);

            jdbcTemplate.update("""
                    INSERT INTO ido.outbox
                        (event_id, event_type, partition_key, aggregate_id,
                         payload, topic, status, created_at)
                    VALUES (?, 'AUTH_COMPLETED', ?, ?, ?::jsonb, ?, 'PENDING', NOW())
                    """,
                    eventId, qimUserId, authResultId, payload, authEventsTopic
            );

            // 즉시 Kafka 발행 시도 (Outbox relay 보완 — 장애 시 relay가 재처리)
            publishAuthEvent(eventId, authResultId, correlationId, authLevel, providerCode, qimUserId);

        } catch (Exception e) {
            log.error("[KeycloakOidcService] outbox 이벤트 저장 실패: correlationId={}", correlationId, e);
            // Outbox 저장 실패 → 전체 트랜잭션 롤백
            throw new PlatformException(PlatformErrorCode.QS_AUTH_FAILED, correlationId,
                    "Outbox 이벤트 저장 실패: " + e.getMessage());
        }
    }

    /**
     * Kafka AUTH_COMPLETED 이벤트 즉시 발행
     */
    private void publishAuthEvent(String eventId, String authResultId, String correlationId,
                                   String authLevel, String providerCode, String qimUserId) {
        try {
            AuthEvent event = new AuthEvent(
                    AuthEvent.TYPE_AUTH_COMPLETED,
                    SOURCE_SYSTEM,
                    correlationId,
                    qimUserId,        // 실제 Q-IM 사용자 ID — Handoff/PolicyEngine에서 정확한 DI 계산 가능
                    1L,               // eventVersion
                    authResultId,
                    AuthResult.AuthLevel.valueOf(authLevel),
                    providerCode,
                    null,             // providerTxId
                    AuthResult.VerificationResult.SUCCESS
            );
            kafkaTemplate.send(authEventsTopic, qimUserId, event);
            log.debug("[KeycloakOidcService] Kafka 이벤트 발행: eventId={} correlationId={}", eventId, correlationId);
        } catch (Exception e) {
            // Kafka 발행 실패는 경고만 — Outbox relay가 재처리
            log.warn("[KeycloakOidcService] Kafka 즉시 발행 실패 (Outbox relay 재처리 예정): correlationId={}", correlationId, e);
        }
    }

    /**
     * OIDC 세션 로그 기록 (감사 목적)
     * 테이블: ido.oidc_session_log (V3 마이그레이션으로 생성)
     */
    private void saveOidcSessionLog(String correlationId, String providerCode,
                                     String sub, String identifierHash, String authResultId) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ido.oidc_session_log
                        (log_id, correlation_id, provider_code, provider_subject,
                         identifier_hash, auth_result_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, NOW())
                    ON CONFLICT (log_id) DO NOTHING
                    """,
                    UuidV7.generate(),
                    correlationId, providerCode, sub,
                    identifierHash, authResultId
            );
        } catch (Exception e) {
            // 로그 저장 실패는 경고만 — 인증 흐름 중단하지 않음
            log.warn("[KeycloakOidcService] oidc_session_log 저장 실패 (무시): correlationId={}", correlationId, e);
        }
    }

    /**
     * AUTH_COMPLETED 이벤트 페이로드 JSON 생성
     */
    private String buildAuthEventPayload(String eventId, String authResultId, String correlationId,
                                          String authLevel, String providerCode,
                                          String qimUserId, String identifierHash) {
        try {
            Map<String, Object> payload = Map.of(
                    "eventId",         eventId,
                    "eventType",       AuthEvent.TYPE_AUTH_COMPLETED,
                    "sourceSystem",    SOURCE_SYSTEM,
                    "correlationId",   correlationId,
                    "authResultId",    authResultId,
                    "authLevel",       authLevel,
                    "providerCode",    providerCode,
                    "qimUserId",       qimUserId,
                    "occurredAt",      Instant.now().toString()
            );
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("이벤트 페이로드 직렬화 실패", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 결과 DTO
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Keycloak 콜백 처리 결과 — KeycloakCallbackController로 반환
     */
    @lombok.Builder
    @lombok.Getter
    public static class CallbackResult {
        private final FeSession feSession;
        private final String    authResultId;
        private final String    authLevel;
        private final String    providerCode;
        private final String    correlationId;
        private final String    returnUrl;
    }
}
