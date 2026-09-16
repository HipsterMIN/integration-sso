package io.github.hipstermin.idem.plugin.anyid;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.util.Map;

/** 복호화된 ssob 필드 해석 — SDK 비의존 순수 함수. */
public final class AnyIdSsob {

    private AnyIdSsob() {
    }

    /**
     * {@code ci} 필드 (평문 CI). 비어 있으면 실패.
     *
     * @param correlationId 오류 컨텍스트
     */
    public static String extractCi(Map<String, Object> ssob, String correlationId) {
        Object ci = ssob.get("ci");
        if (ci == null || ci.toString().isBlank()) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "ssob에 CI 없음 — 인증 결과 처리 불가");
        }
        return ci.toString();
    }

    /**
     * {@code authLvl} → L1/L2/L3.
     * <ul>
     *   <li>1 → L1 (간편인증·민간ID)</li>
     *   <li>2 → L2 (모바일 신분증·PASS)</li>
     *   <li>3 → L3 (공동인증서·금융인증서)</li>
     * </ul>
     */
    public static String extractAuthLevel(Map<String, Object> ssob) {
        Object raw = ssob.get("authLvl");
        if (raw == null) return "L1";
        String val = raw.toString().trim();
        return switch (val) {
            case "1" -> "L1";
            case "2" -> "L2";
            case "3" -> "L3";
            default  -> val.toUpperCase().startsWith("L") ? val.toUpperCase() : "L1";
        };
    }

    /** {@code name} 필드 (화면 표시용, 없으면 빈 문자열). */
    public static String extractName(Map<String, Object> ssob) {
        Object name = ssob.get("name");
        return name == null ? "" : name.toString();
    }
}
