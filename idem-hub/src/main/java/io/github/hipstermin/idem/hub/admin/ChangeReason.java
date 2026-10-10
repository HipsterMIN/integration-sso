package io.github.hipstermin.idem.hub.admin;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 1.1.1 G2-3 — {@code X-Change-Reason} 헤더 값 정규화. HTTP 헤더 값은 ASCII 만 허용되므로 콘솔은 한글 사유를
 * percent-encoding({@code encodeURIComponent}) 으로 보낸다. 여기서 풀어 감사·이력에는 원문을 남긴다.
 * {@code %} 가 없는 값(종전 API 호출자의 ASCII 사유)은 손대지 않는다 — {@code +} 를 공백으로 바꾸지 않기 위해 {@link URLDecoder} 는
 * {@code %} 가 있을 때만 쓴다.
 */
public final class ChangeReason {

    private static final int MAX = 500;

    private ChangeReason() {}

    public static String decode(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if (v.indexOf('%') >= 0) {
            try {
                v = URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException e) {
                // 잘못된 인코딩은 원문 그대로 (사유는 감사 메타데이터일 뿐 거부 사유가 아니다)
            }
        }
        return v.length() > MAX ? v.substring(0, MAX) : v;
    }
}
