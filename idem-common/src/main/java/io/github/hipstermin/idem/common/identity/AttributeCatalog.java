package io.github.hipstermin.idem.common.identity;

import io.github.hipstermin.idem.common.identity.AttributeDefinition.Sensitivity;
import io.github.hipstermin.idem.common.identity.AttributeDefinition.Source;
import io.github.hipstermin.idem.common.identity.AttributeDefinition.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 코어 속성 카탈로그 (S4, {@code docs/generalization-plan.md} §2.3).
 *
 * <p>기관에 전달할 수 있는 사용자 속성의 <b>전체 목록</b>이다. 기관 프로파일은 이 중 부분집합을 고르고
 * ({@code identity.attributes}) 기관 측 필드명을 붙인다({@code identity.attributeMapping}). 카탈로그에 없는 이름은
 * 프로파일 검증(E-IDO-113)에서 거부된다 — "우리 기관만 쓰는 속성" 은 코어 카탈로그 확장(플러그인, S5/S8) 으로 들어온다.
 *
 * <p>정규 이름은 snake_case 이고, 종전 페이로드가 쓰던 camelCase 는 별칭으로 해석한다. 별칭으로 선언한 기관은 출력 키도
 * 별칭을 유지한다(호환).
 */
public final class AttributeCatalog {

    private static final List<AttributeDefinition> CORE = List.of(
            // ── 티켓(발급 컨텍스트) ─────────────────────────────────────────
            def("qim_user_id", Type.STRING, Source.TICKET, "qimUserId", Sensitivity.NONE, MaskingRule.NONE,
                    Set.of("qimUserId"), "플랫폼 내부 사용자 ID. 기관 간 결합이 가능하므로 필요한 기관만 선언한다"),
            def("auth_level", Type.STRING, Source.TICKET, "authLevel", Sensitivity.NONE, MaskingRule.NONE,
                    Set.of("authLevel"), "인증 수준 L1/L2/L3"),
            def("auth_result_id", Type.STRING, Source.TICKET, "authResultId", Sensitivity.NONE, MaskingRule.NONE,
                    Set.of("authResultId"), "인증 결과 ID (감사 추적)"),
            def("agency_code", Type.STRING, Source.TICKET, "agencyCode", Sensitivity.NONE, MaskingRule.NONE,
                    Set.of("agencyCode"), "기관 코드"),
            def("authenticated_at", Type.TIMESTAMP, Source.TICKET, "issuedAt", Sensitivity.NONE, MaskingRule.NONE,
                    Set.of("issuedAt"), "티켓 발급(인증 확정) 시각 ISO-8601"),
            // ── registry 프로필 — 저장소가 이미 마스킹한 값 ────────────────────
            def("name_masked", Type.STRING, Source.PROFILE, "nameMasked", Sensitivity.PERSONAL, MaskingRule.PRESET,
                    Set.of("nameMasked"), "마스킹된 성명 (홍*동)"),
            def("mobile_masked", Type.STRING, Source.PROFILE, "mobileMasked", Sensitivity.PERSONAL, MaskingRule.PRESET,
                    Set.of("mobileMasked"), "마스킹된 휴대전화 (010-****-5678)"),
            def("nationality_type", Type.STRING, Source.PROFILE, "nationalityType", Sensitivity.PERSONAL, MaskingRule.NONE,
                    Set.of("nationalityType"), "DOMESTIC / FOREIGN"),
            def("birth_year", Type.INTEGER, Source.PROFILE, "birthYear", Sensitivity.PERSONAL, MaskingRule.NONE,
                    Set.of("birthYear"), "출생 연도"),
            def("gender", Type.STRING, Source.PROFILE, "gender", Sensitivity.PERSONAL, MaskingRule.NONE,
                    Set.of(), "MALE / FEMALE / UNKNOWN"),
            // ── registry 주체 키 — 스킴이 맞는 사용자만 값이 있다 ────────────────
            def("email", Type.STRING, Source.SUBJECT, "EMAIL", Sensitivity.PERSONAL, MaskingRule.EMAIL_LOCAL,
                    Set.of(), "이메일 주소 (EMAIL 스킴 사용자). 기본 마스킹 ab***@example.org"),
            def("phone", Type.STRING, Source.SUBJECT, "PHONE", Sensitivity.PERSONAL, MaskingRule.LAST4,
                    Set.of(), "전화번호 (PHONE 스킴 사용자). 기본 뒤 4자리만"),
            def("external_sub", Type.STRING, Source.SUBJECT, "EXTERNAL_SUB", Sensitivity.PERSONAL, MaskingRule.NONE,
                    Set.of(), "외부 IdP 안정 식별자 (EXTERNAL_SUB 스킴 사용자)")
    );

    private static final Map<String, AttributeDefinition> INDEX;

    static {
        Map<String, AttributeDefinition> idx = new LinkedHashMap<>();
        for (AttributeDefinition d : CORE) {
            for (String n : d.allNames()) {
                if (idx.put(n.toLowerCase(Locale.ROOT), d) != null) {
                    throw new IllegalStateException("속성 카탈로그 이름 충돌: " + n);
                }
            }
        }
        INDEX = Collections.unmodifiableMap(idx);
    }

    private AttributeCatalog() {}

    private static AttributeDefinition def(String name, Type type, Source source, String sourceKey,
                                           Sensitivity sensitivity, MaskingRule masking, Set<String> aliases, String desc) {
        return new AttributeDefinition(name, type, source, sourceKey, sensitivity, masking, aliases, desc);
    }

    /** 코어 정의 전체 (정규 이름 순서 고정). */
    public static List<AttributeDefinition> all() {
        return CORE;
    }

    /** 정규 이름·별칭으로 찾는다 (대소문자 무시). */
    public static Optional<AttributeDefinition> find(String nameOrAlias) {
        if (nameOrAlias == null) return Optional.empty();
        return Optional.ofNullable(INDEX.get(nameOrAlias.trim().toLowerCase(Locale.ROOT)));
    }

    /** 별칭이면 정규 이름으로, 모르는 이름이면 empty. */
    public static Optional<String> canonicalName(String nameOrAlias) {
        return find(nameOrAlias).map(AttributeDefinition::name);
    }

    /** 모르는 이름들 — 프로파일 검증 메시지용. */
    public static List<String> unknown(Collection<String> names) {
        return names.stream().filter(n -> find(n).isEmpty()).toList();
    }

    /** 정규 이름 목록 (오류 메시지·문서용). */
    public static List<String> names() {
        return CORE.stream().map(AttributeDefinition::name).toList();
    }
}
