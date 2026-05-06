package com.onepass.qsign.infrastructure;

import com.onepass.common.domain.AuthResult;

import java.util.Optional;

/**
 * Q-Sign AuthResult 저장소 인터페이스 (정본 보존)
 * 설계서 9.3절 — 인증 결과 정본은 Q-Sign이 보유
 */
public interface AuthResultRepository {
    void save(AuthResult authResult);
    Optional<AuthResult> findById(String authResultId);
}
