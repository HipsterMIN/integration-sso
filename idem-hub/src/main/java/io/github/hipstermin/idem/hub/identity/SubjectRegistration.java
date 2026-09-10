package io.github.hipstermin.idem.hub.identity;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import lombok.Builder;

/**
 * hub → registry 사용자 등록 계약 (S4). {@code POST /api/v1/internal/users/register-subject}.
 *
 * <p>스킴에 관계없이 같은 본문을 쓴다. registry 는 {@code identifierHash} 로 기존 사용자를 찾고 없으면 만든다.
 * {@code subjectKey} 원문은 registry 가 암호화해 보관하고 hub 는 보관하지 않는다.
 *
 * @param scheme         registry 저장 스킴 ({@link SubjectScheme#isRegistryKey()})
 * @param subjectKey     주체 키 원문 (CI · 이메일 · 전화 · 외부 sub)
 * @param identifierHash {@link SubjectScheme#identifierHash(String)} — hub 가 계산해 보낸다
 * @param providerCode   인증 제공자 코드
 * @param authResultId   인증 결과 ID
 * @param authLevel      인증 수준
 * @param name           성명 원문 (registry 가 마스킹 저장) — 없으면 null
 * @param phone          전화 원문 (registry 가 마스킹 저장) — 없으면 null
 * @param birthDate      생년월일 원문(YYYYMMDD 등) — registry 는 연도만 저장
 * @param gender         성별 코드 원문
 * @param nationalityType DOMESTIC / FOREIGN
 * @param correlationId  추적 ID
 */
@Builder(toBuilder = true)
public record SubjectRegistration(
        SubjectScheme scheme,
        String subjectKey,
        String identifierHash,
        String providerCode,
        String authResultId,
        AuthResult.AuthLevel authLevel,
        String name,
        String phone,
        String birthDate,
        String gender,
        String nationalityType,
        String correlationId) {

    public SubjectRegistration {
        if (scheme == null || !scheme.isRegistryKey()) {
            throw new IllegalArgumentException("registry 저장 스킴이 아닙니다: " + scheme);
        }
        if (subjectKey == null || subjectKey.isBlank()) {
            throw new IllegalArgumentException("subjectKey 가 비어 있습니다");
        }
        if (identifierHash == null || identifierHash.isBlank()) {
            identifierHash = scheme.identifierHash(subjectKey);
        }
    }

    /** 출생 연도 — YYYYMMDD·YYYY-MM-DD·YYYY 앞 4자리. 해석 불가면 null. */
    public Short birthYear() {
        if (birthDate == null || birthDate.length() < 4) return null;
        try {
            return Short.parseShort(birthDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
