package io.github.hipstermin.idem.hub.conversion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

/**
 * GET /api/v1/conversion/session/{sessionId} 응답 DTO
 *
 * <p>FE Step1이 sessionId로 서버 측 전환 세션 정보를 조회할 때 반환한다.
 * CI / 개인정보 등 민감 데이터는 포함하지 않는다.
 */
@Value
@Builder
public class ConversionSessionResponse {

    /** 기관 회원 ID (FE가 인증 흐름에 사용) */
    @JsonProperty("mbr_id")
    String mbrId;

    /** 전환 완료 후 리다이렉트 URI */
    @JsonProperty("redirect_uri")
    String redirectUri;

    /** 회원 유형: INDIVIDUAL | ENTERPRISE */
    @JsonProperty("user_type")
    String userType;

    /** 기관 코드 */
    @JsonProperty("agency_code")
    String agencyCode;
}
