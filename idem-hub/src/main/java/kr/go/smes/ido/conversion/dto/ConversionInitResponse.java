package kr.go.smes.ido.conversion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * 유관기관 전환 초기화 응답 DTO
 *
 * <p>signed_request 검증 성공 후 FE에 반환하는 ConversionSession 참조 정보.
 * 이후 step2~step8은 conversionSessionId를 이용해 서버 측에서 redirectUri 등 민감 정보를 조회한다.
 */
@Value
@Builder
public class ConversionInitResponse {

    /**
     * ConversionSession 식별자 — Redis에 저장된 세션 키
     * FE ConversionContext에 저장하여 이후 API 호출 시 사용.
     */
    @JsonProperty("conversion_session_id")
    String conversionSessionId;

    /**
     * 유관기관이 지정한 회원 유형
     * "IND" (개인) | "ENT" (기업) | null (사용자 선택)
     */
    @JsonProperty("user_type")
    String userType;

    /**
     * ConversionSession 만료 시각 (UTC ISO-8601)
     * 기본값: 요청 시각 + 30분
     */
    @JsonProperty("expires_at")
    Instant expiresAt;
}
