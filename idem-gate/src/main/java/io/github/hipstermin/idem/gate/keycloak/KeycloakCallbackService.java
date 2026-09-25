package io.github.hipstermin.idem.gate.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuthEvent;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.gate.infrastructure.AuthResultRepository;
import io.github.hipstermin.idem.gate.infrastructure.LockRepository;
import io.github.hipstermin.idem.gate.keycloak.dto.KeycloakIdTokenClaims;
import io.github.hipstermin.idem.gate.keycloak.dto.KeycloakTokenResponse;
import io.github.hipstermin.idem.gate.metrics.AuthMetrics;
import io.github.hipstermin.idem.gate.outbox.QSignOutboxRecord;
import io.github.hipstermin.idem.gate.outbox.QSignOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Keycloak Authorization Code Callback 처리 서비스
 *
 * <p>Keycloak 이 인증 완료 후 q-sign 으로 리다이렉트한 callback 을 처리한다.
 * 카카오·네이버 등 소셜 IdP 와의 실제 OIDC 교환은 Keycloak 이 내부 처리하며,
 * q-sign 은 Keycloak 과만 통신한다.
 *
 * <p><b>처리 순서</b>:
 * <ol>
 *   <li>state 검증 — Redis 1회 소비 (CSRF 방어)</li>
 *   <li>POST Keycloak Token Endpoint → access_token + id_token 교환</li>
 *   <li>Keycloak JWKS RS256 서명 검증 → KeycloakIdTokenClaims</li>
 *   <li>nonce 검증 — replay attack 방어</li>
 *   <li>audience 검증 — idem-gate 포함 여부</li>
 *   <li>exp 검증 — 만료 여부</li>
 *   <li>SHA-256(sub) → identifierHash 계산 (PII 비보관 원칙)</li>
 *   <li>providerCode 결정 — identity_provider 역매핑</li>
 *   <li>잠금 상태 확인</li>
 *   <li>AuthResult + Outbox 저장 (단일 @Transactional)</li>
 *   <li>POST ido /api/internal/v1/oidc/complete → FE 세션 발급 요청</li>
 * </ol>
 *
 * <p><b>금지 사항</b>: kauth.kakao.com, nid.naver.com 등 외부 IdP 직접 HTTP 호출 금지.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakCallbackService {

    private static final String SOURCE_SYSTEM = "idem-gate";
    private static final String TOPIC_AUTH    = "idem.gate.auth.events";

    private final KeycloakStateStore      stateStore;
    private final KeycloakJwksVerifier    jwksVerifier;
    private final KeycloakProperties      keycloakProperties;
    private final AuthResultRepository    authResultRepository;
    private final LockRepository          lockRepository;
    private final QSignOutboxRepository   outboxRepository;
    private final RestTemplate            restTemplate;
    private final ObjectMapper            objectMapper;
    private final AuthMetrics             authMetrics;

    @Value("${idem.gate.hub.base-url:http://localhost:8083}")
    private String idoBaseUrl;

    // D2: 종전 기본값 "ido-internal-secret"(공개 문자열) 으로 실제 서명하던 결함 제거 — 비면 InternalSigVerifier 가드가 처리
    @Value("${idem.gate.hub.internal-sig-secret:}")
    private String internalSigSecret;

    /** D3: gate 가 RP 에 내보내는 공개 issuer — Keycloak KC_HOSTNAME_URL 이 이 값이면 id_token iss 도 이 값이다 */
    @Value("${idem.gate.oidc-front.issuer:}")
    private String publicIssuer;

    // ── 공개 진입점 ─────────────────────────────────────────────────────────

    /**
     * Keycloak callback 처리 메인 메서드
     *
     * @param code  Keycloak 이 전달한 authorization code
     * @param state CSRF 방어용 state (Redis 에 저장된 값과 일치해야 함)
     * @return ido 가 반환한 최종 redirect URL (기관 returnUrl 또는 에러 페이지)
     */
    @Transactional
    public String handleCallback(String code, String state) {

        // ── 1. state 검증 (Redis 1회 소비) ───────────────────────────────
        KeycloakStateEntry stateEntry = stateStore.consumeAndValidate(state)
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.IDP_SIGNATURE_MISMATCH,
                        CorrelationIdHolder.get(),
                        "Keycloak state 검증 실패 — CSRF 위협 또는 TTL 만료"));

        String correlationId  = stateEntry.getCorrelationId();
        String returnUrl      = stateEntry.getReturnUrl();
        String requestedLevel = stateEntry.getRequestedLevel();
        CorrelationIdHolder.set(correlationId);

        log.info("[KeycloakCallback] Callback 처리 시작: correlationId={} provider={}",
                correlationId, stateEntry.getProvider());

        // ── 2. Keycloak Token Endpoint — authorization code 교환 ─────────
        KeycloakTokenResponse tokenResp = exchangeCode(code, stateEntry.getCodeVerifier(), correlationId);
        String idToken = tokenResp.getIdToken();
        if (idToken == null || idToken.isBlank()) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "Keycloak token 응답에 id_token 없음 — openid scope 확인 필요");
        }

        // ── 3. Keycloak JWKS RS256 서명 검증 ─────────────────────────────
        KeycloakIdTokenClaims claims = jwksVerifier.verify(idToken, correlationId);

        // ── 3-b. issuer 검증 (D3) — 우리 Keycloak realm(내부 주소 또는 gate 가 내보내는 공개 issuer)만 ──
        validateIssuer(claims.getIssuer(), correlationId);

        // ── 4. nonce 검증 ─────────────────────────────────────────────────
        if (!stateEntry.getNonce().equals(claims.getNonce())) {
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "nonce 불일치 — replay attack 의심");
        }

        // ── 5. audience 검증 ─────────────────────────────────────────────
        String aud = claims.getAudienceAsString();
        if (aud == null || !aud.contains(keycloakProperties.getClientId())) {
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "audience 불일치: aud=" + aud + ", expected clientId="
                            + keycloakProperties.getClientId());
        }

        // ── 6. exp 검증 ──────────────────────────────────────────────────
        long nowEpoch = System.currentTimeMillis() / 1000L;
        if (claims.getExpiresAt() < nowEpoch) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "Keycloak ID Token 만료: exp=" + claims.getExpiresAt() + " now=" + nowEpoch);
        }

        // ── 7. identifierHash = SHA-256(sub) ─────────────────────────────
        String identifierHash = computeIdentifierHash(claims.getSubject(), correlationId);

        // ── 8. providerCode 결정 (identity_provider 역매핑) ──────────────
        String providerCode = resolveProviderCode(claims.getIdentityProvider(),
                stateEntry.getProvider());

        // ── 9. 잠금 상태 확인 ─────────────────────────────────────────────
        if (lockRepository.isLocked(identifierHash, providerCode)) {
            authMetrics.incrementAuthLocked(providerCode);
            throw new PlatformException(PlatformErrorCode.QS_AUTH_LOCKED, correlationId);
        }

        long startMs = System.currentTimeMillis();

        // ── 10. AuthResult + Outbox 저장 (단일 트랜잭션) ─────────────────
        AuthResult authResult = issueAuthResult(
                correlationId, identifierHash, requestedLevel, providerCode, claims);

        log.info("[KeycloakCallback] AuthResult 발급: authResultId={} providerCode={} correlationId={}",
                authResult.getAuthResultId(), providerCode, correlationId);

        // 인증 성공 메트릭
        authMetrics.incrementAuthSuccess(providerCode,
                authResult.getAuthLevel() != null ? authResult.getAuthLevel().name() : "UNKNOWN");
        authMetrics.recordAuthDuration(providerCode,
                authResult.getAuthLevel() != null ? authResult.getAuthLevel().name() : "UNKNOWN",
                System.currentTimeMillis() - startMs);

        lockRepository.unlock(identifierHash, providerCode);   // D3: 성공 시 실패 카운터 초기화

        // ── 11. ido FE 세션 발급 요청 ────────────────────────────────────
        String redirectUrl = notifyIdoAndGetRedirect(authResult, returnUrl, claims.getSubject(), claims.getSessionId());

        log.info("[KeycloakCallback] 인증 완료: authResultId={} → redirect={}",
                authResult.getAuthResultId(), redirectUrl);
        return redirectUrl;
    }

    // ── 내부: Keycloak Token Endpoint 호출 ──────────────────────────────────

    /**
     * Keycloak Token Endpoint 에 authorization code 를 전송하여 토큰을 교환한다.
     *
     * <p>POST {keycloak.tokenEndpoint} — application/x-www-form-urlencoded
     * <p><b>금지</b>: kauth.kakao.com/oauth/token 등 외부 IdP 직접 호출 금지.
     */
    private KeycloakTokenResponse exchangeCode(String code, String codeVerifier, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type",    "authorization_code");
        body.add("code",          code);
        body.add("redirect_uri",  keycloakProperties.getRedirectUri());
        body.add("client_id",     keycloakProperties.getClientId());
        body.add("client_secret", keycloakProperties.getClientSecret());
        // D3: PKCE — auth-url 이 code_challenge 를 보냈으면(엔트리에 verifier 가 있으면) 같은 verifier 를 낸다
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            body.add("code_verifier", codeVerifier);
        }

        String tokenEndpoint = keycloakProperties.tokenEndpoint();
        log.debug("[KeycloakCallback] Token 교환 요청: endpoint={} correlationId={}",
                tokenEndpoint, correlationId);

        try {
            ResponseEntity<KeycloakTokenResponse> resp = restTemplate.exchange(
                    tokenEndpoint,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    KeycloakTokenResponse.class
            );
            if (resp.getBody() == null) {
                throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                        "Keycloak token endpoint 응답 body 가 null");
            }
            log.info("[KeycloakCallback] Token 교환 성공: correlationId={}", correlationId);
            return resp.getBody();

        } catch (PlatformException e) {
            authMetrics.incrementAuthFailure("KEYCLOAK", AuthMetrics.REASON_IDP_ERROR);
            throw e;
        } catch (Exception e) {
            log.error("[KeycloakCallback] Keycloak token 교환 실패: correlationId={}", correlationId, e);
            authMetrics.incrementAuthFailure("KEYCLOAK", AuthMetrics.REASON_IDP_ERROR);
            throw new PlatformException(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE, correlationId, e);
        }
    }

    // ── 내부: issuer 검증 (D3) ───────────────────────────────────────────────

    /**
     * id_token {@code iss} 가 우리 realm 인지 — Keycloak 내부 주소({@code idem.gate.keycloak.base-url}/realms/{realm}) 또는
     * gate 가 공개하는 issuer({@code idem.gate.oidc-front.issuer}, = KC_HOSTNAME_URL 기준). 서명 검증은 이미 우리 JWKS 로
     * 했지만, 같은 Keycloak 의 다른 realm 토큰이 통과하지 않도록 issuer 를 명시적으로 본다. 비어 있거나 다르면 거부.
     */
    void validateIssuer(String issuer, String correlationId) {
        java.util.Set<String> accepted = acceptedIssuers();
        if (issuer == null || issuer.isBlank() || !accepted.contains(stripTrailingSlash(issuer))) {
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "issuer 불일치: iss=" + issuer + " accepted=" + accepted);
        }
    }

    java.util.Set<String> acceptedIssuers() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        set.add(stripTrailingSlash(keycloakProperties.getBaseUrl()) + "/realms/" + keycloakProperties.getRealm());
        if (publicIssuer != null && !publicIssuer.isBlank()) set.add(stripTrailingSlash(publicIssuer));
        return set;
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ── 내부: identifierHash 계산 ────────────────────────────────────────────

    /**
     * SHA-256(sub) → HexFormat hex 문자열
     *
     * <p>PII 비보관 원칙: sub 원문은 이 메서드 호출 이후 참조 불가.
     * identifierHash 는 Q-IM 조회 키 및 AuthResult SoR 저장에 사용된다.
     */
    private String computeIdentifierHash(String sub, String correlationId) {
        if (sub == null || sub.isBlank()) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "Keycloak ID Token sub 클레임이 없음");
        }
        return CryptoProviders.current().sha256Hex(sub);
    }

    // ── 내부: providerCode 결정 ──────────────────────────────────────────────

    /**
     * Keycloak identity_provider 클레임을 providerCode 로 역매핑한다.
     *
     * <p>역매핑 규칙:
     * <ul>
     *   <li>identity_provider = "social-kakao" → "KAKAO_OIDC"</li>
     *   <li>identity_provider = "social-naver" → "NAVER_OIDC"</li>
     *   <li>identity_provider = "social-pass"  → "PASS_OIDC"</li>
     *   <li>identity_provider = "social-gpki"  → "GPKI_OIDC"</li>
     *   <li>identity_provider 없음 → stateEntry.provider 기반 fallback</li>
     * </ul>
     *
     * @param identityProvider Keycloak ID Token 의 identity_provider 클레임 값
     * @param stateProvider    state 에 저장된 원래 provider 식별자 (fallback)
     * @return 플랫폼 providerCode (예: "KAKAO_OIDC")
     */
    private String resolveProviderCode(String identityProvider, String stateProvider) {
        if (identityProvider != null && !identityProvider.isBlank()) {
            // "social-kakao" → "KAKAO_OIDC" 형태로 역매핑
            String stripped = identityProvider.replaceFirst("^social-", "").toUpperCase();
            return stripped + "_OIDC";
        }
        // identity_provider 클레임이 없는 경우 stateProvider 로 fallback
        if (stateProvider != null && !stateProvider.isBlank()) {
            return stateProvider.toUpperCase() + "_OIDC";
        }
        return "KEYCLOAK_OIDC";  // 최후 fallback
    }

    // ── 내부: AuthResult + Outbox 저장 ──────────────────────────────────────

    private AuthResult issueAuthResult(String correlationId, String identifierHash,
                                       String requestedLevel, String providerCode,
                                       KeycloakIdTokenClaims claims) {
        AuthResult.AuthLevel authLevel = parseAuthLevel(requestedLevel);

        AuthResult result = AuthResult.builder()
                .authResultId(UuidV7.generate())
                .correlationId(correlationId)
                .authLevel(authLevel)
                .providerCode(providerCode)
                .authMethod(AuthResult.resolveAuthMethod(providerCode))
                .providerTxId("kc-" + claims.getSubject().hashCode())
                .identifierHash(identifierHash)
                .authenticatedAt(Instant.now())
                .verificationResult(AuthResult.VerificationResult.SUCCESS)
                .build();

        authResultRepository.save(result);

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
                    .eventId(UuidV7.generate())
                    .eventType(eventType)
                    .partitionKey(result.getIdentifierHash())
                    .aggregateId(result.getAuthResultId())
                    .eventVersion(1L)
                    .payload(payload)
                    .topic(TOPIC_AUTH)
                    .build();

            outboxRepository.save(outbox);
            log.debug("[KeycloakCallback] Outbox 저장: eventId={}", outbox.getEventId());

        } catch (Exception e) {
            log.error("[KeycloakCallback] Outbox 저장 실패: authResultId={}",
                    result.getAuthResultId(), e);
            throw new RuntimeException("Outbox 저장 실패", e);
        }
    }

    // ── 내부: ido FE 세션 발급 요청 ────────────────────────────────────────

    /**
     * ido POST /api/internal/v1/oidc/complete 를 호출하여 FE 세션을 발급받는다.
     * 기존 KakaoOidcBrokerService.notifyIdoAndGetRedirect() 와 동일한 방식.
     *
     * @return ido 가 반환한 최종 redirect URL
     */
    private String notifyIdoAndGetRedirect(AuthResult authResult, String returnUrl, String idpSub, String idpSid) {
        String url = idoBaseUrl + "/api/internal/v1/oidc/complete";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id",  authResult.getCorrelationId());
        headers.set("X-Internal-Caller", SOURCE_SYSTEM);
        headers.set("X-Internal-Sig",    buildInternalSig(authResult.getCorrelationId()));

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("authResultId",   authResult.getAuthResultId());
        body.put("identifierHash", authResult.getIdentifierHash());
        body.put("authLevel",      authResult.getAuthLevel().name());
        body.put("providerCode",   authResult.getProviderCode());
        body.put("correlationId",  authResult.getCorrelationId());
        body.put("returnUrl",      returnUrl != null ? returnUrl : "");
        // S6 PR-2: hub FE 세션이 Keycloak sub·sid 를 기억해 SLO·Back-Channel Logout 이 정확히 그 세션을 찾는다
        if (idpSub != null) body.put("idpSub", idpSub);
        if (idpSid != null) body.put("idpSid", idpSid);

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            if (resp.getBody() != null && resp.getBody().containsKey("redirectUrl")) {
                return (String) resp.getBody().get("redirectUrl");
            }
            // ido 응답에 redirectUrl 없으면 returnUrl 직접 사용
            return returnUrl != null ? returnUrl : "/error";

        } catch (Exception e) {
            log.error("[KeycloakCallback] ido 알림 실패: correlationId={}",
                    authResult.getCorrelationId(), e);
            // ido 알림 실패 시에도 AuthResult 는 이미 저장됨 — 에러 페이지로 안전 이동
            return "/error?code=IDO_NOTIFY_FAILED&cid=" + authResult.getCorrelationId();
        }
    }

    // ── 내부 유틸 ────────────────────────────────────────────────────────────

    private AuthResult.AuthLevel parseAuthLevel(String level) {
        if (level == null || level.isBlank()) return AuthResult.AuthLevel.L1;
        try {
            return AuthResult.AuthLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AuthResult.AuthLevel.L1;
        }
    }

    /**
     * HMAC-SHA256(correlationId + ":" + epochSeconds, secret) → Hex 문자열
     * 설계서 §9.4 — Q-Sign → IdO 내부 서명 규칙
     *
     * <p>서명 페이로드: "{correlationId}:{epochSeconds}"
     * <p>수신 측(IdO)은 동일 secret 으로 HMAC 을 재계산하고 타임스탬프 ±60초 유효성도 검사해야 한다.
     */
    private String buildInternalSig(String correlationId) {
        try {
            long epochSeconds = System.currentTimeMillis() / 1000L;
            String payload = correlationId + ":" + epochSeconds;
            return CryptoProviders.current().hmacSha256Hex(internalSigSecret.getBytes(StandardCharsets.UTF_8), payload);
        } catch (Exception e) {
            log.error("[KeycloakCallback] 내부 서명 생성 실패: correlationId={}", correlationId, e);
            throw new IllegalStateException("HMAC-SHA256 내부 서명 생성 실패", e);
        }
    }
}
