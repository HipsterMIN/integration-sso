package kr.go.smes.qsign.infrastructure;

/**
 * Q-Sign 잠금/재시도 정책 저장소
 * 설계서 9.6절 참조 — Redis 기반 분산 락
 */
public interface LockRepository {
    boolean isLocked(String identifierHash, String providerCode);
    void incrementAttempt(String identifierHash, String providerCode);
    void lock(String identifierHash, String providerCode);
    void unlock(String identifierHash, String providerCode);
}
