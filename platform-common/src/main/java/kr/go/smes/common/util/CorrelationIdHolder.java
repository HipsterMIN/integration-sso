package kr.go.smes.common.util;

import java.util.UUID;

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
        return UUID.randomUUID().toString();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
