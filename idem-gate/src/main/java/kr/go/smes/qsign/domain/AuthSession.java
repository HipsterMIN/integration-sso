package kr.go.smes.qsign.domain;

import kr.go.smes.common.domain.AuthResult;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Q-Sign 인증 세션 (진행 중 상태 관리)
 * 설계서 9.3절 참조
 */
@Getter
@Builder
public class AuthSession {

    private final String sessionId;
    private final String correlationId;
    private final String providerCode;
    private final AuthResult.AuthLevel requestedLevel;
    private final AuthSessionState state;
    private final int attemptCount;
    private final Instant startedAt;
    private final Instant expiresAt;

    public enum AuthSessionState {
        INITIATED,
        PROVIDER_REDIRECTED,
        COMPLETED,
        FAILED,
        LOCKED
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
