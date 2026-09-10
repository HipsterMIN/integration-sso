package io.github.hipstermin.idem.common.identity;

/**
 * 기관에 속성을 내보내기 직전 적용하는 마스킹 규칙 (S4).
 *
 * <p>registry 가 이미 마스킹해 보관하는 값({@code name_masked} 등)은 {@link #PRESET} 으로 그대로 나가고,
 * 원문이 흐르는 값(이메일·전화)은 카탈로그 기본 규칙이 적용된다. 기관은 프로파일 {@code identity.attributes[].masking}
 * 으로 기본을 바꿀 수 있다 — 단 {@link #NONE} 으로 낮추는 것은 기관 계약(개인정보 제공 근거)이 있다는 뜻이므로 감사에 남긴다.
 */
public enum MaskingRule {
    /** 마스킹하지 않는다. */
    NONE,
    /** 저장소가 이미 마스킹한 값 — 추가 처리 없음. */
    PRESET,
    /** 앞 1자·뒤 1자만 남기고 가운데를 '*' 로. 2자 이하는 마지막 글자만 '*'. */
    PARTIAL,
    /** 뒤 4자만 남긴다. */
    LAST4,
    /** 이메일 local-part 의 앞 2자만 남긴다 ({@code ab***@example.org}). */
    EMAIL_LOCAL;

    public String apply(String value) {
        if (value == null || value.isEmpty()) return value;
        return switch (this) {
            case NONE, PRESET -> value;
            case PARTIAL -> {
                int n = value.length();
                if (n <= 2) yield value.charAt(0) + "*".repeat(n - 1);
                yield value.charAt(0) + "*".repeat(n - 2) + value.charAt(n - 1);
            }
            case LAST4 -> {
                int n = value.length();
                if (n <= 4) yield "*".repeat(n);
                yield "*".repeat(n - 4) + value.substring(n - 4);
            }
            case EMAIL_LOCAL -> {
                int at = value.indexOf('@');
                if (at <= 0) yield PARTIAL.apply(value);
                String local = value.substring(0, at);
                String kept = local.substring(0, Math.min(2, local.length()));
                yield kept + "***" + value.substring(at);
            }
        };
    }
}
