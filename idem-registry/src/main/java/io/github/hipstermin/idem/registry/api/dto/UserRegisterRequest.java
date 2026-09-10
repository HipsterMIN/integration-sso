package io.github.hipstermin.idem.registry.api.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * 사용자 등록 요청 DTO (IdO → Q-IM 내부 API)
 * POST /api/v1/internal/users · POST /api/v1/internal/users/register-subject (S4, 스킴 중립)
 */
@Getter
@Builder
@Jacksonized
public class UserRegisterRequest {

    /** 인증 결과 ID (Q-Sign 발급) */
    private final String authResultId;

    /** 인증 수단 식별자 해시 (SHA-256, PII 비노출) */
    private final String identifierHash;

    /** 인증 제공자 코드 (KAKAO_OIDC / PASS / FINANCIAL_CERT 등) */
    private final String providerCode;

    /** 인증 수준 (L1 / L2 / L3) */
    private final String authLevel;

    /** 평문 CI — Q-IM에서 AES-256-GCM 암호화 저장 (전송 후 즉시 파기). S4 부터는 {@code scheme=CI, subjectKey} 와 같은 뜻 */
    private final String rawCi;

    /** S4: 주체 식별자 스킴 (CI / EMAIL / PHONE / EXTERNAL_SUB). 없으면 rawCi 가 있을 때 CI, 아니면 EXTERNAL_SUB */
    private final String scheme;

    /** S4: 주체 키 원문 — 스킴에 따라 CI·이메일·전화·외부 sub. 암호화 저장 후 파기 */
    private final String subjectKey;

    /** 원본 이름 — Q-IM에서 마스킹 후 저장 */
    private final String rawName;

    /** 원본 전화번호 — Q-IM에서 마스킹 후 저장 */
    private final String rawMobile;

    /** 국적 구분 (DOMESTIC / FOREIGN) */
    private final String nationalityType;

    /** 출생 연도 */
    private final Short birthYear;

    /** 성별 (MALE / FEMALE / UNKNOWN) */
    private final String gender;

    /** 전체 흐름 추적 ID */
    private final String correlationId;
}
