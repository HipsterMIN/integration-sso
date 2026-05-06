package kr.go.smes.qsign.broker.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.event.AuthEvent;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.qsign.broker.oidc.dto.KakaoIdTokenClaims;
import kr.go.smes.qsign.broker.oidc.dto.KakaoTokenResponse;
import kr.go.smes.qsign.broker.state.OidcStateEntry;
import kr.go.smes.qsign.broker.state.OidcStateStore;
import kr.go.smes.qsign.infrastructure.AuthResultRepository;
import kr.go.smes.qsign.infrastructure.LockRepository;
import kr.go.smes.qsign.outbox.QSignOutboxRecord;
import kr.go.smes.qsign.outbox.QSignOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 카카오 OIDC Authorization Code Flow 브로커 서비스
 *
 * <p>책임 범위:
 * <ol>
 *   <li>Authorization URL 발급 (state/nonce 생성 + Redis 저장)</li>
 *   <li>Callback 처리 — code→token 교환 → idToken 검증 → AuthResult 발급</li>
 *   <li>Outbox 에 AuthEvent 저장 (Transactional Outbox 패턴)</li>
 *   <li>ido 내부 API 호출 → FE 세션 발급 요청</li>
 * </ol>
 *
 * <p>흐름 다이어그램 (§17.1 / §9.7):
 * <pre>
 *   FE(browser) → ido /api/v1/broker/kakao/authorize
 *               ← 302 → kauth.kakao.com/oauth/authorize
 *   FE(browser) → [카카오 로그인]
 *               → q-sign /api/v1/oidc/kakao/callback?code=&state=
 *                  ↳ code 교환 → idToken 검증 → AuthResult DB 저장
 *                  ↳ Outbox 저장 (AUTH_COMPLETED 이벤트)
 *                  ↳ ido POST /api/internal/v1/oidc/complete
 *               ← ido: FE세션 발급 → feSessionId 쿠키 → 302 returnUrl
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoOidcBrokerService {

    private static final String PROVIDER_CODE  = "KAKAO_OIDC";
    private static final String SOURCE_SYSTEM  = "q-sign";
    private static final String TOPIC_AUTH     = "qsign.auth.events";

    private final KakaoOidcClient        kakaoOidcClient;
    private final OidcStateStore         stateStore;
    private final AuthResultRepository   authResultRepository;
    private final LockRepository         lockRepository;
    private final QSignOutboxRepository  outboxRepository;
    private final RestTemplate           restTemplate;
    private final ObjectMapper           objectMapper;

    @Value("${qsign.ido.base-url:http://localhost:8083}")
    private String idoBaseUrl;

    @Value("${qsign.ido.internal-sig-secret:ido-internal-secret}")
    private String internalSigSecret;

    // ── 1. Authorization URL 발급 ─────────────────────────────────────────

    /**
     * 카카오 로그인 Authorization URL 반환
     *
     * @param correlationId 흐름 추적 ID
     * @param returnUrl     인증 완료 후 이동할 기관 URL
     * @param requestedLevel 요청 인증 수준 (기본 L1)
     * @return 브라우저가 리다이렉트해야 할 카카오 Authorization URL
     */
    public String buildAuthorizationUrl(String correlationId, String returnUrl, String requestedLevel) {
        OidcStateEntry stateEntry = stateStore.create(correlationId, returnUrl, requestedLevel);
        String authUrl = kakaoOidcClient.buildAuthorizationUrl(stateEntry.getState(), stateEntry.getNonce());
        log.info("[KakaoOidcBroker] Authorization URL 발급: correlationId={} returnUrl={}",
                correlationId, returnUrl);
        return authUrl;
    }

    // ── 2. Callback 처리 ─────────────────────────────────────────────────

    /**
     * 카카오 Authorization Code Callback 처리
     *
     * <p>처리 순서:
     * <ol>
     *   <li>state 검증 (CSRF 방어)</li>
     *   <li>Authorization Code → Token 교환</li>
     *   <li>ID Token 서명·claims 검증 (issuer / aud / nonce / exp)</li>
     *   <li>잠금 상태 확인</li>
     *   <li>AuthResult 발급 + Outbox 저장 (동일 트랜잭션)</li>
     *   <li>ido 내부 API 호출 → FE 세션 발급</li>
     * </ol>
     *
     * @param code  카카오 authorization code
     * @param state CSRF 방어 state
     * @return 인증 완료 후 리다이렉트할 최종 URL (기관 returnUrl)
     */
    @Transactional
    public String handleCallback(String code, String state) {
        // ── state 검증 ────────────────────────────────────────────────────
        OidcStateEntry stateEntry = stateStore.consumeAndValidate(state)
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.IDP_SIGNATURE_MISMATCH,
                        CorrelationIdHolder.get(),
                        "OIDC state 검증 실패 — CSRF 위협 또는 만료"));

        String correlationId   = stateEntry.getCorrelationId();
        String returnUrl       = stateEntry.getReturnUrl();
        String requestedLevel  = stateEntry.getRequestedLevel();
        CorrelationIdHolder.set(correlationId);

        log.info("[KakaoOidcBroker] Callback 수신: correlationId={}", correlationId);

        // ── code → token 교환 ─────────────────────────────────────────────
        KakaoTokenResponse tokenResp = kakaoOidcClient.exchangeCode(code, correlationId);

        // ── idToken 검증 ──────────────────────────────────────────────────
        KakaoIdTokenClaims claims = kakaoOidcClient.verifyAndParseClaims(
                tokenResp.getIdToken(), stateEntry.getNonce(), correlationId);

        // ── identifierHash 계산 (SHA-256(sub)) ───────────────────────────
        String identifierHash = kakaoOidcClient.computeIdentifierHash(claims.getSubject());

        // ── 잠금 확인 ─────────────────────────────────────────────────────
        if (lockRepository.isLocked(identifierHash, PROVIDER_CODE)) {
            throw new PlatformException(PlatformErrorCode.QS_AUTH_LOCKED, correlationId);
        }

        // ── AuthResult 발급 + Outbox 저장 (동일 DB 트랜잭션) ─────────────
        AuthResult authResult = issueAuthResult(
                correlationId, identifierHash, requestedLevel, claims, tokenResp.getIdToken());

        // ── ido 내부 호출: FE 세션 발급 요청 ─────────────────────────────
        String redirectUrl = notifyIdoAndGetRedirect(authResult, returnUrl);

        log.info("[KakaoOidcBroker] 인증 완료: authResultId={} correlationId={}",
                authResult.getAuthResultId(), correlationId);
        return redirectUrl;
    }

    // ── 내부: AuthResult 발급 + Outbox ────────────────────────────────────

    private AuthResult issueAuthResult(String correlationId, String identifierHash,
                                        String requestedLevel, KakaoIdTokenClaims claims,
                                        String idToken) {
        AuthResult.AuthLevel authLevel = parseAuthLevel(requestedLevel);

        AuthResult result = AuthResult.builder()
                .authResultId(UUID.randomUUID().toString())
                .correlationId(correlationId)
                .authLevel(authLevel)
                .providerCode(PROVIDER_CODE)
                .providerTxId(claims.getSubject() != null
                        ? "kakao-" + claims.getSubject().hashCode()
                        : UUID.randomUUID().toString())
                .identifierHash(identifierHash)
                .authenticatedAt(Instant.now())
                .verificationResult(AuthResult.VerificationResult.SUCCESS)
                .build();

        authResultRepository.save(result);

        // Outbox 저장 (AUTH_COMPLETED)
        saveOutboxEvent(result, AuthEvent.TYPE_AUTH_COMPLETED);

        return result;
    }

    private void saveOutboxEvent(AuthResult result, String eventType) {
        try {
            AuthEvent event = new AuthEvent(
                    eventType, SOURCE_SYSTEM,
                    result.getCorrelationId(), result.getIdentifierHash(), 1L,
                    result.getAuthResultId(), result.getAuthLevel(),
                    result.getProviderCode(), result.getProviderTxId(),
                    result.getVerificationResult()
            );
            String payload = objectMapper.writeValueAsString(event);

            QSignOutboxRecord outbox = QSignOutboxRecord.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(eventType)
                    .partitionKey(result.getIdentifierHash())
                    .aggregateId(result.getAuthResultId())
                    .eventVersion(1L)
                    .payload(payload)
                    .topic(TOPIC_AUTH)
                    .build();

            outboxRepository.save(outbox);
            log.debug("[KakaoOidcBroker] Outbox 저장: eventId={}", outbox.getEventId());

        } catch (Exception e) {
            log.error("[KakaoOidcBroker] Outbox 저장 실패: authResultId={}", result.getAuthResultId(), e);
            throw new RuntimeException("Outbox 저장 실패", e);
        }
    }

    // ── 내부: ido FE 세션 발급 요청 ──────────────────────────────────────

    /**
     * ido POST /api/internal/v1/oidc/complete 호출
     * ido 가 FE 세션을 발급하고 최종 redirect URL 을 반환한다.
     *
     * @return ido 가 반환한 최종 redirect URL (기관 returnUrl 또는 에러 페이지)
     */
    private String notifyIdoAndGetRedirect(AuthResult authResult, String returnUrl) {
        String url = idoBaseUrl + "/api/internal/v1/oidc/complete";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", authResult.getCorrelationId());
        headers.set("X-Internal-Caller", "q-sign");
        // 간단한 HMAC 서명 (PoC 수준 — 실운영에서는 mTLS + HMAC-SHA256 적용)
        headers.set("X-Internal-Sig", buildInternalSig(authResult.getCorrelationId()));

        Map<String, Object> body = Map.of(
                "authResultId",    authResult.getAuthResultId(),
                "identifierHash",  authResult.getIdentifierHash(),
                "authLevel",       authResult.getAuthLevel().name(),
                "providerCode",    authResult.getProviderCode(),
                "correlationId",   authResult.getCorrelationId(),
                "returnUrl",       returnUrl != null ? returnUrl : ""
        );

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            if (resp.getBody() != null && resp.getBody().containsKey("redirectUrl")) {
                return (String) resp.getBody().get("redirectUrl");
            }
            // fallback: returnUrl 직접 사용
            return returnUrl != null ? returnUrl : "/error";

        } catch (Exception e) {
            log.error("[KakaoOidcBroker] ido 알림 실패: correlationId={}",
                    authResult.getCorrelationId(), e);
            // ido 알림 실패 시에도 인증 결과는 이미 저장됨 — 에러 페이지로 안전 이동
            return "/error?code=IDO_NOTIFY_FAILED&cid=" + authResult.getCorrelationId();
        }
    }

    // ── 내부 유틸 ──────────────────────────────────────────────────────────

    private AuthResult.AuthLevel parseAuthLevel(String level) {
        if (level == null || level.isBlank()) return AuthResult.AuthLevel.L1;
        try {
            return AuthResult.AuthLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AuthResult.AuthLevel.L1;
        }
    }

    private String buildInternalSig(String correlationId) {
        // PoC 수준 — 실운영에서는 HMAC-SHA256(correlationId + timestamp, secret) 사용
        return "sig-" + correlationId.replace("-", "").substring(0, 8);
    }
}
