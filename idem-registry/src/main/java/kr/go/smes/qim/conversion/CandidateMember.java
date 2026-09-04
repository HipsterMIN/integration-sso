package kr.go.smes.qim.conversion;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/**
 * 유관 시스템의 기존 회원 후보 정보
 * (MEMBERS_FETCHED 단계에서 후보 목록을 구성할 때 사용)
 *
 * <p>PII 노출 최소화: 이름은 마스킹 처리, 회원 ID는 서비스 내부 ID (원본 노출 없음).
 */
@Getter
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CandidateMember {

    /** 유관 시스템 기관 코드 (예: "GOV_AGENCY_01") */
    private final String agencyCode;

    /** 기관 내 서비스 명칭 (예: "소상공인 지원포털") */
    private final String agencyName;

    /** 기관 내부 회원 ID (서비스 내부 식별자 — PII 아님) */
    private final String memberId;

    /** 이름 마스킹 (예: "홍*동") */
    private final String nameMasked;

    /** 최근 로그인 일시 (nullable) */
    private final Instant lastLoginAt;

    /** 가입 일시 (nullable) */
    private final Instant joinedAt;
}
