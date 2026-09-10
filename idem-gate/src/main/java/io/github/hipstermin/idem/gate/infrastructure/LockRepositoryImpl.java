package io.github.hipstermin.idem.gate.infrastructure;

import io.github.hipstermin.idem.gate.infrastructure.jpa.entity.AuthLockJpaEntity;
import io.github.hipstermin.idem.gate.infrastructure.jpa.repository.AuthLockJpaRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q-Sign LockRepository JPA 구현체
 * 설계서 §9.6 — 인증 수단별 잠금·재시도 카운터 (PostgreSQL qsign.auth_lock)
 *
 * lockKey = {identifierHash}:{providerCode}
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LockRepositoryImpl implements LockRepository {

    private static final short DEFAULT_MAX_ATTEMPTS = 5;

    private final AuthLockJpaRepository jpaRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isLocked(String identifierHash, String providerCode) {
        String lockKey = buildKey(identifierHash, providerCode);
        return jpaRepository.findById(lockKey)
                .map(e -> {
                    if (!e.isLocked()) return false;
                    // unlock_at 이 지났으면 자동 해제 간주
                    if (e.getUnlockAt() != null && e.getUnlockAt().isBefore(Instant.now())) {
                        log.info("[Q-Sign Lock] TTL 만료 → 자동 해제 간주 lockKey={}", lockKey);
                        return false;
                    }
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional
    public void incrementAttempt(String identifierHash, String providerCode) {
        String lockKey = buildKey(identifierHash, providerCode);
        Instant now = Instant.now();

        // upsert: 레코드 없으면 신규 생성
        AuthLockJpaEntity entity = jpaRepository.findById(lockKey)
                .orElseGet(() -> AuthLockJpaEntity.builder()
                        .lockKey(lockKey)
                        .attemptCount((short) 0)
                        .maxAttempts(DEFAULT_MAX_ATTEMPTS)
                        .locked(false)
                        .updatedAt(now)
                        .build());

        entity.setAttemptCount((short) (entity.getAttemptCount() + 1));
        entity.setLastAttemptAt(now);
        entity.setUpdatedAt(now);

        jpaRepository.save(entity);
        log.debug("[Q-Sign Lock] 시도 횟수 증가 lockKey={} attemptCount={}",
                lockKey, entity.getAttemptCount());
    }

    @Override
    @Transactional
    public void lock(String identifierHash, String providerCode) {
        String lockKey = buildKey(identifierHash, providerCode);
        Instant now = Instant.now();

        AuthLockJpaEntity entity = jpaRepository.findById(lockKey)
                .orElseGet(() -> AuthLockJpaEntity.builder()
                        .lockKey(lockKey)
                        .attemptCount((short) 0)
                        .maxAttempts(DEFAULT_MAX_ATTEMPTS)
                        .updatedAt(now)
                        .build());

        entity.setLocked(true);
        entity.setLockedAt(now);
        entity.setUpdatedAt(now);
        jpaRepository.save(entity);
        log.warn("[Q-Sign Lock] 잠금 처리 lockKey={}", lockKey);
    }

    @Override
    @Transactional
    public void unlock(String identifierHash, String providerCode) {
        String lockKey = buildKey(identifierHash, providerCode);
        Instant now = Instant.now();

        jpaRepository.findById(lockKey).ifPresent(entity -> {
            entity.setLocked(false);
            entity.setUnlockAt(now);
            entity.setAttemptCount((short) 0);
            entity.setUpdatedAt(now);
            jpaRepository.save(entity);
            log.info("[Q-Sign Lock] 잠금 해제 lockKey={}", lockKey);
        });
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────

    private String buildKey(String identifierHash, String providerCode) {
        return identifierHash + ":" + providerCode;
    }
}
