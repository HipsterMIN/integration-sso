package io.github.hipstermin.idem.registry.conversion;

import lombok.Builder;
import lombok.Getter;

/**
 * 유관 기관 등록 정보 (AgencyMemberLookupServiceImpl 내부 사용)
 *
 * <p>실제 운영 시 DB 또는 설정 파일에서 로드.
 * PoC 단계에서는 {@link AgencyRegistry}에 하드코딩된 68개 기관 목록 사용.
 */
@Getter
@Builder
public class AgencyRegistration {

    /** 기관 코드 (예: GOV_SMES, GOV_MSS, GOV_MOEL) */
    private final String agencyCode;

    /** 기관 명칭 (표시용) */
    private final String agencyName;

    /**
     * 기관 API 베이스 URL
     * (예: http://agency-stub:8083, https://api.smes.go.kr)
     */
    private final String baseUrl;

    /** 기관 API 인증 키 (X-Agency-Key 헤더) */
    private final String apiKey;

    /** 개별 기관 API 타임아웃 (ms) — 기본 3초 */
    @Builder.Default
    private final int timeoutMs = 3_000;

    /** 활성 여부 (false면 조회 건너뜀) */
    @Builder.Default
    private final boolean active = true;
}
