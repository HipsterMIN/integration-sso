package kr.go.smes.sdk.agency.idempotency;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OnePass Agency SDK 멱등성 키 생성기
 *
 * <p>각 API 요청에 고유한 {@code X-Idempotency-Key}를 생성한다.
 * 동일 키로 재요청 시 서버가 멱등 처리(중복 무시)하므로,
 * <b>재시도 시 반드시 동일 키를 재사용해야 한다</b>.
 *
 * <h3>생성 전략</h3>
 * <ul>
 *   <li><b>기본 (JDK 8+)</b>: {@code UUID.randomUUID()} — RFC 4122 Version 4 (랜덤)</li>
 *   <li><b>접두사 포함</b>: {@code {prefix}-{uuid}} — 운영 로그 추적 용이</li>
 *   <li><b>시퀀스 기반</b>: {@code {agencyCode}-{epochMs}-{seq}} — 디버그 친화적</li>
 * </ul>
 *
 * <h3>서버 TTL</h3>
 * <p>OnePass 서버는 멱등성 키를 24시간 보관한다.
 * 같은 키를 24시간 이내에 재사용하면 중복으로 처리된다.
 *
 * <p><b>JDK 버전 호환: Java 8+</b>
 */
public final class IdempotencyKeyGenerator {

    /** 프로세스 시작 시각 (시퀀스 기반 키 충돌 방지용 베이스) */
    private static final long PROCESS_START_MS = System.currentTimeMillis();

    /** 시퀀스 카운터 (멀티스레드 안전) */
    private static final AtomicLong SEQUENCE = new AtomicLong(0L);

    // 인스턴스화 방지
    private IdempotencyKeyGenerator() {}

    /**
     * UUID v4 기반 랜덤 멱등성 키 생성 (권장 방식)
     *
     * <p>예: {@code "550e8400-e29b-41d4-a716-446655440000"}
     *
     * @return 36자 UUID v4 문자열
     */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * 접두사 포함 UUID v4 키 생성 (운영 로그 추적 용이)
     *
     * <p>예: {@code "MOIS-550e8400-e29b-41d4-a716-446655440000"}
     *
     * @param prefix 접두사 (기관 코드 권장, 예: {@code "MOIS"}, {@code "NTS"})
     * @return {@code "{prefix}-{uuid}"} 형식 문자열
     */
    public static String generateWithPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return generate();
        }
        return prefix.toUpperCase() + "-" + UUID.randomUUID().toString();
    }

    /**
     * 시퀀스 기반 멱등성 키 생성 (디버그 친화적, 시간순 정렬 가능)
     *
     * <p>예: {@code "MOIS-1700000000000-0000000042"}
     *
     * <p>프로세스 시작 시각 + 단조 증가 시퀀스를 조합하므로,
     * 재시작 시 시각 기반 구간이 달라져 충돌 가능성이 낮다.
     * 단, UUID 대비 랜덤성이 낮으므로 <b>운영 환경에서는 UUID 방식 권장</b>.
     *
     * @param agencyCode 기관 코드 (예: {@code "MOIS"})
     * @return {@code "{agencyCode}-{epochMs}-{seq10자리}"} 형식
     */
    public static String generateSequential(String agencyCode) {
        long seq = SEQUENCE.getAndIncrement();
        return (agencyCode != null ? agencyCode.toUpperCase() : "SDK")
                + "-" + PROCESS_START_MS
                + "-" + String.format("%010d", seq);
    }

    /**
     * 두 멱등성 키가 동일한지 상수시간 비교.
     *
     * <p>타이밍 공격을 방어하기 위해 {@code String.equals()} 대신
     * 상수시간 바이트 비교를 사용한다.
     *
     * @param key1 비교 대상 1
     * @param key2 비교 대상 2
     * @return 동일하면 {@code true}
     */
    public static boolean constantTimeEquals(String key1, String key2) {
        if (key1 == null || key2 == null) return key1 == key2;
        byte[] b1 = key1.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        byte[] b2 = key2.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        return java.security.MessageDigest.isEqual(b1, b2);
    }
}
