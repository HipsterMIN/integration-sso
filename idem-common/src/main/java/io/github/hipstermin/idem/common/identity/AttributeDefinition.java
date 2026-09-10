package io.github.hipstermin.idem.common.identity;

import java.util.List;
import java.util.Set;

/**
 * 속성 카탈로그 항목 (S4).
 *
 * @param name           정규 이름 — 소문자 snake_case. Tenant Profile {@code identity.attributes} 가 참조한다
 * @param type           값 형식 (문서화·콘솔 폼용)
 * @param source         값을 어디서 가져오는가
 * @param sourceKey      원천 안에서의 키 (TICKET: 티켓 필드명, PROFILE: registry 응답 키, SUBJECT: 스킴 이름)
 * @param sensitivity    민감도 — 감사·기본 마스킹의 근거
 * @param defaultMasking 프로파일이 지정하지 않을 때 적용되는 마스킹
 * @param legacyAliases  종전 {@code allowed_attributes}·페이로드에서 쓰던 이름 (camelCase). 프로파일에서 이 이름으로 선언하면
 *                       출력 키도 그 이름을 유지해 기존 기관 연동이 깨지지 않는다
 * @param description    설명
 */
public record AttributeDefinition(
        String name,
        Type type,
        Source source,
        String sourceKey,
        Sensitivity sensitivity,
        MaskingRule defaultMasking,
        Set<String> legacyAliases,
        String description) {

    public enum Type { STRING, INTEGER, TIMESTAMP }

    /** 값의 원천. */
    public enum Source {
        /** Handoff 티켓(발급 시점 컨텍스트) — 추가 조회 없음. */
        TICKET,
        /** registry 사용자 프로필({@code GET /internal/users/{id}}) — 지연 조회. */
        PROFILE,
        /** registry 주체 키({@code GET /internal/users/{id}/subject?scheme=}) — 스킴이 맞는 사용자만 값이 있다. */
        SUBJECT
    }

    /** 민감도. PERSONAL 이상은 기관 프로파일에 명시적으로 선언해야만 나간다(카탈로그 기본 노출 없음). */
    public enum Sensitivity { NONE, PERSONAL, SENSITIVE }

    public AttributeDefinition {
        legacyAliases = legacyAliases == null ? Set.of() : Set.copyOf(legacyAliases);
    }

    /** 이 정의를 가리키는 모든 이름 (정규 이름 + 별칭). */
    public List<String> allNames() {
        List<String> names = new java.util.ArrayList<>();
        names.add(name);
        names.addAll(legacyAliases);
        return names;
    }

    /** SUBJECT 원천일 때 대응하는 스킴. */
    public SubjectScheme subjectScheme() {
        return source == Source.SUBJECT ? SubjectScheme.valueOf(sourceKey) : null;
    }
}
