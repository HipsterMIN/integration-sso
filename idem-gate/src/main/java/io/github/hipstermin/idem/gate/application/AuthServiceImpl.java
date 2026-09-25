package io.github.hipstermin.idem.gate.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.IdOAuthInput;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuthEvent;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.gate.infrastructure.AuthResultRepository;
import io.github.hipstermin.idem.gate.infrastructure.LockRepository;
import io.github.hipstermin.idem.gate.metrics.AuthMetrics;
import java.time.Instant;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q-Sign AuthService 구현체
 * 설계서 9.3 / 9.5 / 9.6절 참조
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String TOPIC_AUTH_EVENTS = "qsign.auth.events";
    private static final String SOURCE_SYSTEM     = "q-sign";

    private final AuthResultRepository authResultRepository;
    private final LockRepository       lockRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuthMetrics authMetrics;

    /**
     * OIDC 인증 결과 발급 (레거시 경로 — /api/v1/auth/oidc 엔드포인트)
     *
     * <p>Keycloak 브로커 흐름(흐름 A)에서는 이 메서드가 호출되지 않는다.
     * Keycloak 흐름은 {@code KeycloakCallbackService.handleCallback()} 이 직접 처리한다.
     * 이 메서드는 흐름 B(NonOidc broker-input 경로)에서만 사용된다.
     *
     * <p>idToken 의 {@code sub} 클레임을 파싱하여 {@code SHA-256(sub)} 으로 identifierHash 를 산출한다.
     * Task 3-2: 임시 {@code SHA-256(providerCode + ":" + correlationId)} 해시 제거.
     */
    @Override
    @Transactional
    public AuthResult issueFromOidc(String correlationId, String providerCode,
                                    String idToken, String requestedLevel) {
        log.info("[Q-Sign] OIDC 인증 시작 correlationId={} provider={}", correlationId, providerCode);

        // Task 3-2: idToken sub 파싱 → SHA-256(sub) 기반 identifierHash
        // PII 비보관 원칙: sub 원문은 hash 계산 직후 GC 대상이 됨
        String sub = extractSubFromIdToken(idToken, correlationId);
        String identifierHash = computeIdentifierHash(sub);

        // D3: 잠금은 (identifierHash, providerCode) 키다 — 종전엔 providerCode 를 식별자 자리에 넣어 항상 미잠금이었다
        if (lockRepository.isLocked(identifierHash, providerCode)) {
            authMetrics.incrementAuthLocked(providerCode);
            throw new PlatformException(PlatformErrorCode.QS_AUTH_LOCKED, correlationId);
        }
        long startMs = System.currentTimeMillis();

        AuthResult result = AuthResult.builder()
                .authResultId(UuidV7.generate())
                .correlationId(correlationId)
                .authLevel(AuthResult.AuthLevel.valueOf(requestedLevel))
                .providerCode(providerCode)
                .authMethod(AuthResult.resolveAuthMethod(providerCode))
                .identifierHash(identifierHash)
                .authenticatedAt(Instant.now())
                .verificationResult(AuthResult.VerificationResult.SUCCESS)
                .build();

        authResultRepository.save(result);
        publishAuthEvent(result, AuthEvent.TYPE_AUTH_COMPLETED);

        authMetrics.incrementAuthSuccess(providerCode, requestedLevel);
        authMetrics.recordAuthDuration(providerCode, requestedLevel, System.currentTimeMillis() - startMs);

        log.info("[Q-Sign] 인증 결과 발급 authResultId={}", result.getAuthResultId());
        return result;
    }

    @Override
    @Transactional
    public AuthResult issueFromIdOAuthInput(IdOAuthInput input) {
        log.info("[Q-Sign] IdOAuthInput 수신 correlationId={} provider={}",
                input.getCorrelationId(), input.getProviderCode());

        // 흐름 B (NonOidc broker-input) — PASS, GPKI 등 비OIDC 인증 정규화 입력
        // internalSignature 는 X-Internal-Sig 헤더로 수신 (AuthController 에서 검증)
        // identifierHash 는 ido NonOidcBroker 가 CI 기반으로 계산하여 전달

        if (lockRepository.isLocked(input.getIdentifierHash(), input.getProviderCode())) {
            authMetrics.incrementAuthLocked(input.getProviderCode());
            throw new PlatformException(PlatformErrorCode.QS_AUTH_LOCKED, input.getCorrelationId());
        }

        if (!input.isProviderVerified()) {
            // D3: 검증 실패를 센다 — 임계치(5)에 닿으면 LockRepository 가 잠근다. 종전엔 카운터를 올리는 호출자가 없었다
            if (input.getIdentifierHash() != null && !input.getIdentifierHash().isBlank()) {
                lockRepository.incrementAttempt(input.getIdentifierHash(), input.getProviderCode());
            }
            authMetrics.incrementAuthFailure(input.getProviderCode(), AuthMetrics.REASON_INVALID_RESPONSE);
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, input.getCorrelationId());
        }

        long startMs = System.currentTimeMillis();
        String authLevelTag = input.getRequestedAuthLevel() != null
                ? input.getRequestedAuthLevel().name() : "UNKNOWN";

        AuthResult result = AuthResult.builder()
                .authResultId(UuidV7.generate())
                .correlationId(input.getCorrelationId())
                .authLevel(input.getRequestedAuthLevel())
                .providerCode(input.getProviderCode())
                .authMethod(AuthResult.resolveAuthMethod(input.getProviderCode()))
                .providerTxId(input.getProviderTxId())
                .identifierHash(input.getIdentifierHash())
                .authenticatedAt(Instant.now())
                .verificationResult(AuthResult.VerificationResult.SUCCESS)
                .build();

        authResultRepository.save(result);
        publishAuthEvent(result, AuthEvent.TYPE_AUTH_COMPLETED);
        lockRepository.unlock(input.getIdentifierHash(), input.getProviderCode());   // D3: 성공 시 실패 카운터 초기화

        authMetrics.incrementAuthSuccess(input.getProviderCode(), authLevelTag);
        authMetrics.recordAuthDuration(input.getProviderCode(), authLevelTag, System.currentTimeMillis() - startMs);

        log.info("[Q-Sign] IdOAuthInput 기반 인증 결과 발급 authResultId={}", result.getAuthResultId());
        return result;
    }

    @Override
    public AuthResult findById(String authResultId, String correlationId) {
        return authResultRepository.findById(authResultId)
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.QS_AUTH_FAILED, correlationId,
                        "AuthResult not found: " + authResultId));
    }

    @Override
    public boolean isLocked(String identifierHash, String providerCode) {
        return lockRepository.isLocked(identifierHash, providerCode);
    }

    // ── 내부 유틸 ────────────────────────────────────────────────────────────

    /**
     * ID Token(JWT)에서 {@code sub} 클레임을 추출한다.
     *
     * <p>서명 검증은 호출 전 InternalSigVerifier / KeycloakCallbackService 에서 이미 수행됨.
     * 이 메서드는 페이로드 Base64 디코딩만 수행한다 (서명 검증 스킵).
     * 세부 import: 스프링 의존성에 포함된 Jackson ObjectMapper 사용.
     *
     * @throws PlatformException sub 클레임 없거나 파싱 실패 시
     */
    private String extractSubFromIdToken(String idToken, String correlationId) {
        try {
            // JWT 구조: header.payload.signature — payload는 1번 인덱스
            String[] parts = idToken.split("\\.", -1);
            if (parts.length < 2) {
                throw new PlatformException(
                        PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                        "idToken format invalid");
            }
            // Base64Url 디코딩 (패딩 없이)
            byte[] payloadBytes = Base64.getUrlDecoder().decode(
                    parts[1].replace("-", "+").replace("_", "/")
                            + "=".repeat((4 - parts[1].length() % 4) % 4));
            ObjectMapper mapper = new ObjectMapper();
            JsonNode claims = mapper.readTree(payloadBytes);
            JsonNode subNode = claims.get("sub");
            if (subNode == null || subNode.isNull() || subNode.asText().isBlank()) {
                throw new PlatformException(
                        PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                        "idToken sub claim is missing");
            }
            return subNode.asText();
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Q-Sign] idToken sub 파싱 실패 correlationId={}", correlationId, e);
            throw new PlatformException(
                    PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "idToken 파싱 실패: " + e.getMessage());
        }
    }

    /**
     * SHA-256(input) → Hex 문자열
     * PII 비보관 원칙: 원문 input 은 이 메서드 호출 이후 참조 금지.
     */
    private String computeIdentifierHash(String input) {
        return CryptoProviders.current().sha256Hex(input);
    }

    private void publishAuthEvent(AuthResult result, String eventType) {
        AuthEvent event = new AuthEvent(
                eventType, SOURCE_SYSTEM,
                result.getCorrelationId(), result.getIdentifierHash(), 1L,
                result.getAuthResultId(), result.getAuthLevel(),
                result.getProviderCode(), result.getProviderTxId(),
                result.getVerificationResult()
        );
        kafkaTemplate.send(TOPIC_AUTH_EVENTS, result.getIdentifierHash(), event);
    }
}
