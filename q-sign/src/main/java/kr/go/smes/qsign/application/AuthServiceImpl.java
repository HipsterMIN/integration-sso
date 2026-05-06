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

import java.time.Instant;
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

    @Override
    @Transactional
    public AuthResult issueFromOidc(String correlationId, String providerCode,
                                    String idToken, String requestedLevel) {
        log.info("[Q-Sign] OIDC 인증 시작 correlationId={} provider={}", correlationId, providerCode);

        // TODO: 외부 OIDC Provider 검증 (idToken claims 검증)
        // TODO: identifierHash 계산

        if (lockRepository.isLocked("TODO_IDENTIFIER_HASH", providerCode)) {
            throw new PlatformException(PlatformErrorCode.QS_AUTH_LOCKED, correlationId);
        }

        AuthResult result = AuthResult.builder()
                .authResultId(UUID.randomUUID().toString())
                .correlationId(correlationId)
                .authLevel(AuthResult.AuthLevel.valueOf(requestedLevel))
                .providerCode(providerCode)
                .identifierHash("TODO_IDENTIFIER_HASH")
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

        // TODO: internalSignature 검증 (mTLS + X-Internal-Sig)
        // TODO: identifierHash 재검증

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
