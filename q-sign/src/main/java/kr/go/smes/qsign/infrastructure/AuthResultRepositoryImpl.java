package kr.go.smes.qsign.infrastructure;

import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.qsign.infrastructure.jpa.entity.AuthResultJpaEntity;
import kr.go.smes.qsign.infrastructure.jpa.repository.AuthResultJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Q-Sign AuthResultRepository JPA 구현체
 * 설계서 §9.3 — 인증 결과 SoR (PostgreSQL qsign.auth_result)
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AuthResultRepositoryImpl implements AuthResultRepository {

    private final AuthResultJpaRepository jpaRepository;

    @Override
    public void save(AuthResult authResult) {
        AuthResultJpaEntity entity = toEntity(authResult);
        jpaRepository.save(entity);
        log.debug("[Q-Sign] AuthResult 저장: authResultId={} providerCode={}",
                authResult.getAuthResultId(), authResult.getProviderCode());
    }

    @Override
    public Optional<AuthResult> findById(String authResultId) {
        return jpaRepository.findById(authResultId)
                .map(this::toDomain);
    }

    // ── 매핑 헬퍼 ──────────────────────────────────────────────────────────

    private AuthResultJpaEntity toEntity(AuthResult d) {
        return AuthResultJpaEntity.builder()
                .authResultId(d.getAuthResultId())
                .correlationId(d.getCorrelationId())
                .authLevel(d.getAuthLevel() != null ? d.getAuthLevel().name() : "L1")
                .providerCode(d.getProviderCode())
                .providerTxId(d.getProviderTxId())
                .identifierHash(d.getIdentifierHash())
                .verificationResult(d.getVerificationResult() != null
                        ? d.getVerificationResult().name() : "SUCCESS")
                .requestedAt(Instant.now())
                .authenticatedAt(d.getAuthenticatedAt())
                .internalSignature(d.getSignature())
                .sessionRef(d.getSessionRef())
                .authMethod(d.getAuthMethod())
                .build();
    }

    private AuthResult toDomain(AuthResultJpaEntity e) {
        return AuthResult.builder()
                .authResultId(e.getAuthResultId())
                .correlationId(e.getCorrelationId())
                .authLevel(e.getAuthLevel() != null
                        ? AuthResult.AuthLevel.valueOf(e.getAuthLevel()) : null)
                .providerCode(e.getProviderCode())
                .providerTxId(e.getProviderTxId())
                .identifierHash(e.getIdentifierHash())
                .authenticatedAt(e.getAuthenticatedAt())
                .verificationResult(e.getVerificationResult() != null
                        ? AuthResult.VerificationResult.valueOf(e.getVerificationResult()) : null)
                .sessionRef(e.getSessionRef())
                .signature(e.getInternalSignature())
                .authMethod(e.getAuthMethod())
                .build();
    }
}
