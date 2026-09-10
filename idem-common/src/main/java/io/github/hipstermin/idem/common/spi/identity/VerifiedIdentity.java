package io.github.hipstermin.idem.common.spi.identity;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 표준화된 본인인증 결과. 벤더 형식(NICE ResultData 등)은 여기로 정규화된다.
 *
 * @param providerCode 제공자 코드
 * @param txId         제공자 트랜잭션 ID
 * @param subjectKey   동일인 판정 키. KR 에디션은 CI(또는 DI), 범용 코어는 이메일·전화·외부 sub 등 — 어떤 종류인지는
 *                     {@code subjectScheme} 이 말하고, registry 등록은 hub 의 SubjectRegistrationService(S4) 가 한다
 * @param name         성명
 * @param birthDate    생년월일 (YYYYMMDD, 제공자 원문 형식 유지)
 * @param gender       성별 코드 (제공자 원문)
 * @param phone        휴대전화 번호
 * @param phoneCarrier 통신사 코드
 * @param level        보증 등급
 * @param verifiedAt   인증 시각
 * @param attributes   제공자별 부가 속성 (내·외국인 구분 등) — null 이면 빈 맵. 개인정보 원문(CI 등)은 넣지 않는다
 * @param subjectScheme {@code subjectKey} 의 종류 ({@link SubjectScheme#isRegistryKey() registry 저장 스킴}). null 이면
 *                     {@link SubjectScheme#EXTERNAL_SUB} — 제공자가 준 안정 식별자로 본다
 */
public record VerifiedIdentity(
        String providerCode,
        String txId,
        String subjectKey,
        String name,
        String birthDate,
        String gender,
        String phone,
        String phoneCarrier,
        AuthResult.AuthLevel level,
        Instant verifiedAt,
        Map<String, String> attributes,
        SubjectScheme subjectScheme) {

    public VerifiedIdentity {
        attributes = attributes == null ? Collections.emptyMap() : Map.copyOf(attributes);
        subjectScheme = subjectScheme == null ? SubjectScheme.EXTERNAL_SUB : subjectScheme;
        if (!subjectScheme.isRegistryKey()) {
            throw new IllegalArgumentException("subjectScheme 은 registry 저장 스킴이어야 합니다: " + subjectScheme);
        }
    }

    /** 스킴 없이 만드는 종전 생성자 — {@link SubjectScheme#EXTERNAL_SUB} 로 본다. */
    public VerifiedIdentity(String providerCode, String txId, String subjectKey, String name, String birthDate,
                            String gender, String phone, String phoneCarrier, AuthResult.AuthLevel level,
                            Instant verifiedAt, Map<String, String> attributes) {
        this(providerCode, txId, subjectKey, name, birthDate, gender, phone, phoneCarrier, level, verifiedAt,
                attributes, null);
    }
}
