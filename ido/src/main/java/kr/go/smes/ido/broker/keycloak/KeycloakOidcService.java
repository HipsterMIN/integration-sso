package kr.go.smes.ido.broker.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.event.AuthEvent;
import kr.go.smes.ido.broker.BrokerAuditLogService;
import kr.go.smes.ido.broker.keycloak.dto.KeycloakJwtClaims;
import kr.go.smes.ido.broker.keycloak.dto.KeycloakTokenResponse;
import kr.go.smes.ido.broker.state.IdoOidcStateEntry;
import kr.go.smes.ido.broker.state.IdoOidcStateStore;
import kr.go.smes.ido.fe.session.FeSession;
import kr.go.smes.ido.fe.session.FeSessionService;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import kr.go.smes.common.util.UuidV7;

/**
 * Keycloak OIDC 콜백 처리 서비스 (문서 §7, §8)
 *
 * <p>Keycloak → ido GET /api/v1/broker/callback 콜백 수신 후 전 과정 처리:
 * <ol>
 *   <li>state 검증 (Redis 1회 소비 — CSRF 방지)</li>
 *   <li>authorization code → token 교환 (Keycloak Token Endpoint)</li>
 *   <li>id_token JWKS 서명 검증 + nonce 검증 (replay attack 방지)</li>
 *   <li>identifierHash 생성 (SHA-256(sub))</li>
 *   <li>AuthResult 생성 → ido.auth_result 저장 (Keycloak 모드 Strategy B)</li>
 *   <li>Outbox 이벤트 저장 → Kafka qsign.auth.events 발행</li>
 *   <li>FE 세션 생성 → feSessionId 쿠키 발급 준비</li>
 * </ol>
 *
 * <p>Strategy B (문서 §8.3):
 * Keycloak 도입 후 q-sign이 더 이상 콜백을 수신하지 않으므로,
 * IdO가 직접 AuthResult를 생성하고 {@code qsign.auth.events} Kafka 토픽에 발행.
 * 기존 Q-IM, Handoff 처리 등 하위 컨슈머(QsignAuthEventConsumer 등)는 변경 없음.
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

        // ── 6. identifierHash 생성 (SHA-256(sub)) ────────────────────────
        String identifierHash = computeIdentifierHash(claims.getSubject(), correlationId);

        // ── 7. providerCode 결정 ─────────────────────────────────────────
        String providerCode = resolveProviderCode(claims, stateEntry);

        // ── 8. AuthResult 생성 + DB 저장 (Strategy B) ────────────────────
        String authResultId = UuidV7.generate();
        String authLevel    = keycloakProperties.resolveAuthLevel(claims.getAcr());
        String authMethod   = kr.go.smes.common.domain.AuthResult.resolveAuthMethod(providerCode);
        Instant issuedAt    = claims.getIssuedAt() > 0 ? Instant.ofEpochSecond(claims.getIssuedAt()) : null;
        Instant expiresAt   = claims.getExpiresAt() > 0 ? Instant.ofEpochSecond(claims.getExpiresAt()) : null;

        saveAuthResult(authResultId, correlationId, authLevel, providerCode,
                identifierHash, claims.getSubject(),
                authMethod, issuedAt, expiresAt, tokenResponse.getIdToken());

        // ── 9. Outbox 이벤트 저장 → Kafka 발행 ───────────────────────────
        saveOutboxEvent(authResultId, correlationId, authLevel, providerCode, identifierHash);

        // ── 10. FE 세션 생성 ─────────────────────────────────────────────
        // [설계 주의] Keycloak 소셜 로그인 경로에서는 CI가 없으므로 Q-IM qimUserId를
        // 직접 조회할 수 없다. identifierHash(SHA-256(sub))를 세션 식별자로 사용한다.
        // 이는 소셜 로그인 전용 설계이며, 본인인증(CI 기반) 경로는 OidcCompleteController
        // 의 resolveQimUserId()가 실제 qimUserId를 조회/등록한다.
        // → Q-IM 팀과 소셜 로그인 사용자 식별 전략 협의 필요 (현재 identifierHash 사용)
        log.debug("[KeycloakOidcService] 소셜 로그인 세션 생성: identifierHash(prefix)={} authResultId={}",
                identifierHash.length() >= 8 ? identifierHash.substring(0, 8) : identifierHash,
                authResultId);
        FeSession feSession = feSessionService.create(
                identifierHash,   // 소셜 로그인 경로: CI 없음 → identifierHash 사용 (Q-IM 팀 협의 필요)
                authResultId,
                authLevel,
                stateEntry.getReturnUrl()
        );

        // ── 11. OIDC 세션 로그 기록 ───────────────────────────────────────
        saveOidcSessionLog(correlationId, providerCode, claims.getSubject(),
                identifierHash, authResultId);

        // ── 12. broker_audit_log COMPLETE 기록 (P1) ──────────────────────
        brokerAuditLogService.recordComplete(
                correlationId, providerCode, "STANDARD_OIDC",
                claims.getSubject(), identifierHash, authLevel, "keycloak", null
        );

        log.info("[KeycloakOidcService] 인증 완료: correlationId={} authResultId={} authLevel={}",
                correlationId, authResultId, authLevel);

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
     * identifierHash 생성: SHA-256(sub) → hex encoding
     */
    private String computeIdentifierHash(String sub, String correlationId) {
        if (sub == null || sub.isBlank()) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "id_token sub 클레임 없음");
        }
        try {
            MessageDigest md    = MessageDigest.getInstance("SHA-256");
            byte[]        hash  = md.digest(sub.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("identifierHash 생성 실패", e);
        }
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
                                  String identifierHash) {
        try {
            String eventId  = UuidV7.generate();
            String payload  = buildAuthEventPayload(eventId, authResultId, correlationId,
                    authLevel, providerCode, identifierHash);

            jdbcTemplate.update("""
                    INSERT INTO ido.outbox
                        (event_id, event_type, partition_key, aggregate_id,
                         payload, topic, status, created_at)
                    VALUES (?, 'AUTH_COMPLETED', ?, ?, ?::jsonb, ?, 'PENDING', NOW())
                    """,
                    eventId, identifierHash, authResultId, payload, authEventsTopic
            );

            // 즉시 Kafka 발행 시도 (Outbox relay 보완 — 장애 시 relay가 재처리)
            publishAuthEvent(eventId, authResultId, correlationId, authLevel, providerCode, identifierHash);

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
                                   String authLevel, String providerCode, String identifierHash) {
        try {
            AuthEvent event = new AuthEvent(
                    AuthEvent.TYPE_AUTH_COMPLETED,
                    SOURCE_SYSTEM,
                    correlationId,
                    identifierHash,   // 소셜 로그인: CI 없음 → identifierHash 사용 (Q-IM 팀 협의 필요)
                    1L,               // eventVersion
                    authResultId,
                    AuthResult.AuthLevel.valueOf(authLevel),
                    providerCode,
                    null,             // providerTxId — Keycloak 모드에서 sub는 identifierHash에 포함
                    AuthResult.VerificationResult.SUCCESS
            );
            kafkaTemplate.send(authEventsTopic, identifierHash, event);
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
                                          String authLevel, String providerCode, String identifierHash) {
        try {
            Map<String, Object> payload = Map.of(
                    "eventId",         eventId,
                    "eventType",       AuthEvent.TYPE_AUTH_COMPLETED,
                    "sourceSystem",    SOURCE_SYSTEM,
                    "correlationId",   correlationId,
                    "authResultId",    authResultId,
                    "authLevel",       authLevel,
                    "providerCode",    providerCode,
                    "identifierHash",  identifierHash,
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
