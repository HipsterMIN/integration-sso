package io.github.hipstermin.idem.common.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * 주체 식별자 스킴 (범용화 S4, {@code docs/generalization-plan.md} §2.3).
 *
 * <p>두 자리에서 쓰인다.
 * <ul>
 *   <li><b>registry 저장 키</b>({@link #isRegistryKey()}): 사람을 registry 안에서 유일하게 가리키는 값의 종류.
 *       KR 에디션은 {@link #CI}, 범용 코어는 {@link #EMAIL}·{@link #PHONE}·{@link #EXTERNAL_SUB}.
 *       값은 registry 가 암호화해 보관하고 조회는 {@link #identifierHash(String)} 로만 한다.</li>
 *   <li><b>기관향 식별자</b>({@link #isTenantSelectable()}): Tenant Profile {@code identity.subjectScheme} 가 고르는,
 *       Handoff {@code subject.agencySubjectId} 의 종류. 기본은 {@link #PAIRWISE_HMAC}(기관별 가명, 현 DI) 이다.
 *       {@link #CI} 는 registry 밖으로 평문이 나가지 않으므로 기관향으로 고를 수 없다.</li>
 * </ul>
 */
public enum SubjectScheme {

    /** 기관별 가명 식별자 — HMAC(기관코드:플랫폼ID). OIDC pairwise sub 와 같은 성질. 기관향 기본값. */
    PAIRWISE_HMAC(false, true),
    /** 플랫폼 내부 불변 ID(qimUserId) 그대로. 여러 기관이 같은 값을 받으므로 기관 간 결합이 가능하다 — 명시적으로만 선택. */
    PLATFORM_ID(false, true),
    /** 연계정보(KR). registry 저장 전용 — 기관향 선택 불가. */
    CI(true, false),
    /** 이메일 주소. 저장 시 소문자·공백 제거로 정규화한다. */
    EMAIL(true, true),
    /** 전화번호. 저장 시 숫자만 남긴다. */
    PHONE(true, true),
    /** 외부 IdP(OIDC 등)가 준 안정 식별자(sub). */
    EXTERNAL_SUB(true, true);

    private final boolean registryKey;
    private final boolean tenantSelectable;

    SubjectScheme(boolean registryKey, boolean tenantSelectable) {
        this.registryKey = registryKey;
        this.tenantSelectable = tenantSelectable;
    }

    /** 기관향 기본 스킴. */
    public static final SubjectScheme DEFAULT = PAIRWISE_HMAC;

    /** registry 에 값이 저장되는 스킴인가. */
    public boolean isRegistryKey() { return registryKey; }

    /** Tenant Profile 이 기관향 식별자로 고를 수 있는가. */
    public boolean isTenantSelectable() { return tenantSelectable; }

    /** 저장 값 없이 플랫폼 ID 로부터 파생되는가 (PAIRWISE_HMAC·PLATFORM_ID). */
    public boolean isDerived() { return !registryKey; }

    /** 대소문자·공백 무시. 모르는 값은 empty. */
    public static Optional<SubjectScheme> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 저장·해시 전 정규화. 같은 사람이 표기만 다르게 들어와도 같은 키가 되도록 한다.
     * EMAIL: trim + 소문자. PHONE: 숫자만(국가번호 '+' 는 제거). 그 외: trim.
     */
    public String normalizeKey(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        return switch (this) {
            case EMAIL -> v.toLowerCase(Locale.ROOT);
            case PHONE -> v.replaceAll("[^0-9]", "");
            default -> v;
        };
    }

    /**
     * registry {@code auth_mean_mapping.identifier_hash} 값 — SHA-256 hex(64자).
     *
     * <p>{@link #CI}·{@link #EXTERNAL_SUB} 는 기존 데이터와의 호환을 위해 원문 그대로 해시한다
     * ({@code sha256(ci)}, {@code sha256(sub)}). 새로 도입한 {@link #EMAIL}·{@link #PHONE} 은
     * 스킴 접두를 붙여({@code "EMAIL:" + 정규화 값}) 스킴 간 충돌을 막는다. 파생 스킴은 해시가 없다.
     */
    public String identifierHash(String rawKey) {
        if (!registryKey) {
            throw new IllegalStateException(name() + " 은(는) registry 저장 키가 아니라 identifierHash 가 없습니다");
        }
        String normalized = normalizeKey(rawKey);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(name() + " 주체 키가 비어 있습니다");
        }
        String input = switch (this) {
            case CI, EXTERNAL_SUB -> normalized;
            default -> name() + ":" + normalized;
        };
        return sha256Hex(input);
    }

    static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
