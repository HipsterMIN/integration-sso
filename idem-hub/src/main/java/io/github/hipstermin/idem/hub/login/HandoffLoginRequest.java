package io.github.hipstermin.idem.hub.login;

import java.time.Instant;
import lombok.Builder;

/**
 * 1.1 코어 로그인 프런트 — 브라우저 진입 한 건의 상태 (Redis, 10분).
 *
 * @param requestId    진입 식별자 (쿼리 {@code req})
 * @param agencyCode   대상 Service
 * @param callbackUrl  기관 콜백 (화이트리스트 검증 완료)
 * @param requestedLevel 기관이 요구한 인증수준 (L1~L3)
 * @param providerCode 고른 제공자 — SPI 코드, 또는 {@code broker:<name>}
 * @param txId         SPI initiate 가 준 트랜잭션 id (broker 경로는 null)
 * @param state        기관이 준 불투명 값 — 콜백에 그대로 되돌린다
 * @param correlationId 이 진입의 상관관계 ID
 */
@Builder(toBuilder = true)
public record HandoffLoginRequest(
        String requestId,
        String agencyCode,
        String callbackUrl,
        String requestedLevel,
        String providerCode,
        String txId,
        String state,
        String correlationId,
        Instant createdAt) {

    public static final String BROKER_PREFIX = "broker:";

    public boolean isBrokerProvider() {
        return providerCode != null && providerCode.startsWith(BROKER_PREFIX);
    }

    public String brokerName() {
        return isBrokerProvider() ? providerCode.substring(BROKER_PREFIX.length()) : null;
    }
}
