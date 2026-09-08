package io.github.hipstermin.idem.hub.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 기업 간편인증 콜백 요청 DTO
 *
 * <p>간편인증창(통합인증 서버)이 인증 완료 후 브라우저를 통해 FE로 전달하는 페이로드.
 * FE는 이 데이터를 그대로 {@code POST /api/v1/auth/callback}에 전달한다.
 *
 * <p><b>FE → ido 요청 흐름:</b>
 * <pre>
 * 통합인증창 → FE(postMessage) → POST /api/v1/auth/callback → ido → 통합인증 서버
 * </pre>
 *
 * <p><b>필드 설명:</b>
 * <ul>
 *   <li>{@code siteInfo.siteId}  — 통합인증 서버에서 발급한 사이트 식별자</li>
 *   <li>{@code txId}             — 인증 트랜잭션 ID</li>
 *   <li>{@code tokenId}          — 인증 토큰 ID</li>
 *   <li>{@code userToken}        — 사용자 토큰 (간편인증 결과)</li>
 *   <li>{@code hubToken}         — 허브 토큰 (기관 연동 시 사용)</li>
 * </ul>
 *
 * @see AuthCallbackResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthCallbackRequest {

    private SiteInfo siteInfo;
    private String txId;
    private String tokenId;
    private String userToken;
    private String hubToken;

    /**
     * 사이트 정보 — 통합인증 서버가 발급한 사이트 식별자를 담는 중첩 객체
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SiteInfo {
        /** 통합인증 서버 발급 사이트 ID */
        private String siteId;
    }
}
