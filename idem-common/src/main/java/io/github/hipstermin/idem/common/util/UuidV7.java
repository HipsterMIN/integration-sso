package io.github.hipstermin.idem.common.util;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

/**
 * UUID v7 생성 유틸리티 (RFC 9562)
 *
 * <p><b>v4 vs v7 비교</b>:
 * <pre>
 *   UUID v4: 완전 무작위 — 정렬 불가, DB 인덱스 단편화 발생
 *   UUID v7: 48bit 밀리초 타임스탬프 + 무작위 — 시간순 정렬 가능, B-Tree 인덱스 효율 우수
 *
 *   v7 포맷: {48bit_ms}{4bit=7}{12bit_rand}{2bit=10}{62bit_rand}
 *   예시: 0190b4a2-3c1d-7a4f-9b2e-1c3d4e5f6a7b
 *                       ^--- version=7
 * </pre>
 *
 * <p><b>용도별 가이드라인</b>:
 * <ul>
 *   <li>{@link #generate()} — 비즈니스 ID 전용 (qimUserId, ticketId, eventId, auditId 등)
 *       DB PK·인덱스 정렬 성능 이점 + Kafka 파티션 시간 순서 보장</li>
 *   <li>{@code UUID.randomUUID()} 유지 — 보안 목적 무작위성이 필요한 경우:
 *       OIDC state, nonce, PKCE verifier, traceparent spanId</li>
 * </ul>
 *
 * <p><b>Java 버전 참고</b>: UUID v7 표준 JDK 지원은 Java 24+에서 예정.
 * 현재 Java 21 환경이므로 {@code com.fasterxml.uuid:java-uuid-generator:5.1.0} 사용.
 * JDK 지원 시 {@code UUID.randomUUID7()} 등으로 마이그레이션 가능하도록 이 클래스에 집중.
 *
 * <p><b>스레드 안전성</b>: {@link TimeBasedEpochGenerator}는 내부적으로 synchronized 처리되어
 * 멀티스레드 환경에서 안전하게 사용 가능.
 *
 * <p><b>사용 예시</b>:
 * <pre>{@code
 *   // 비즈니스 ID 생성
 *   String qimUserId  = UuidV7.generate();   // "0190b4a2-3c1d-7xxx-..."
 *   String ticketId   = UuidV7.generate();
 *   String auditId    = UuidV7.generate();
 *
 *   // 보안 무작위성이 필요한 경우 — v4 유지
 *   String state      = UUID.randomUUID().toString().replace("-", "");
 *   String nonce      = UUID.randomUUID().toString().replace("-", "");
 * }</pre>
 */
public final class UuidV7 {

    /** UUID v7 생성기 — 시간 기반 에포크 밀리초 + 무작위 (RFC 9562 §5.7) */
    private static final TimeBasedEpochGenerator GENERATOR =
            Generators.timeBasedEpochGenerator();

    private UuidV7() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * UUID v7 문자열 생성 (하이픈 포함, 소문자)
     *
     * <p>형식: {@code xxxxxxxx-xxxx-7xxx-yxxx-xxxxxxxxxxxx}
     * 첫 48bit는 현재 시각(밀리초), version nibble은 7 고정.
     *
     * @return UUID v7 문자열 (36자)
     */
    public static String generate() {
        return GENERATOR.generate().toString();
    }

    /**
     * UUID v7 문자열 생성 (하이픈 제거, 소문자 32자)
     *
     * <p>하이픈 없는 형식이 필요한 경우(e.g. Redis 키 prefix 등)에 사용.
     *
     * @return UUID v7 문자열 (32자, 하이픈 없음)
     */
    public static String generateCompact() {
        return GENERATOR.generate().toString().replace("-", "");
    }
}
