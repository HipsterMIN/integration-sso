package io.github.hipstermin.idem.common.util;

// correlationId는 UUID v7 사용 — 시간순 정렬로 로그·추적 분석 효율 향상

/**
 * correlationId ThreadLocal 홀더
 * 설계서 8.7 / 9.5절 — 모든 로그에 correlationId 포함 원칙
 */
public final class CorrelationIdHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private CorrelationIdHolder() {}

    public static String get() {
        String id = HOLDER.get();
        if (id == null) {
            id = generate();
            HOLDER.set(id);
        }
        return id;
    }

    public static void set(String correlationId) {
        HOLDER.set(correlationId);
    }

    public static String generate() {
        return UuidV7.generate();  // v7: 시간 정렬 가능 → 로그 시간순 조회 효율
    }

    public static void clear() {
        HOLDER.remove();
    }
}
