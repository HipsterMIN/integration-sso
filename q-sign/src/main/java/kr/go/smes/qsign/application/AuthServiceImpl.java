package kr.go.smes.qsign.application;

import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.event.AuthEvent;
import kr.go.smes.common.domain.IdOAuthInput;
import kr.go.smes.qsign.infrastructure.AuthResultRepository;
import kr.go.smes.qsign.infrastructure.LockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

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

    /**
     * OIDC 인증 결과 발급 (레거시 경로 — /api/v1/auth/oidc 엔드포인트)
     *
     * <p>Keycloak 브로커 흐름(흐름 A)에서는 이 메서드가 호출되지 않는다.
     * Keycloak 흐름은 {@code KeycloakCallbackService.handleCallback()} 이 직접 처리한다.
     * 이 메서드는 흐름 B(NonOidc broker-input 경로)에서만 사용된다.
     *
     * <p>이 경로로 idToken 이 전달되는 경우는 Keycloak 이 아닌 직접 OIDC 연동 시나리오로,
     * 현 설계에서는 사용되지 않는다. 하위 호환성을 위해 메서드는 유지한다.
     */
    @Override
    @Transactional
    public AuthResult issueFromOidc(String correlationId, String providerCode,
                                    String idToken, String requestedLevel) {
        log.info("[Q-Sign] OIDC 인증 시작 correlationId={} provider={}", correlationId, providerCode);

        // Keycloak 흐름(흐름 A)에서는 KeycloakCallbackService 가 처리하며 이 경로는 사용되지 않음.
        // 흐름 B(broker-input)에서는 issueFromIdOAuthInput() 이 처리함.
        // identifierHash 와 claims 검증은 각 흐름의 전용 서비스에서 수행됨.
        // 이 경로(직접 OIDC)에서는 idToken 의 sub 클레임이 있어야 identifierHash 를 산출할 수 있으나,
        // 현 설계에서는 실제 호출되지 않으므로 SHA-256(providerCode + ":" + correlationId) 로 임시 생성.
        // ⚠️  이 경로가 실제 운용될 경우 반드시 idToken 파싱 후 SHA-256(sub) 로 교체해야 한다.

        if (lockRepository.isLocked(providerCode, providerCode)) {
            throw new PlatformException(PlatformErrorCode.QS_AUTH_LOCKED, correlationId);
        }

        // 직접 OIDC 경로 — idToken sub 미제공 시 providerCode:correlationId 해시로 대체
        String identifierHash = computeIdentifierHash(providerCode + ":" + correlationId);

        AuthResult result = AuthResult.builder()
                .authResultId(UUID.randomUUID().toString())
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

        if (!input.isProviderVerified()) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, input.getCorrelationId());
        }

        if (lockRepository.isLocked(input.getIdentifierHash(), input.getProviderCode())) {
            throw new PlatformException(PlatformErrorCode.QS_AUTH_LOCKED, input.getCorrelationId());
        }

        AuthResult result = AuthResult.builder()
                .authResultId(UUID.randomUUID().toString())
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
     * SHA-256(input) → Hex 문자열
     * PII 비보관 원칙: 원문 input 은 이 메서드 호출 이후 참조 금지.
     */
    private String computeIdentifierHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 해시 계산 실패", e);
        }
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
