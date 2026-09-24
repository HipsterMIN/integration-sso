package io.github.hipstermin.idem.hub.kr.conversion.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 유관기관 → OnePass 회원 전환 초기화 요청 DTO
 *
 * <p>유관기관이 자체 로그인 완료 후 JWT Signed Request(HS256)를 구성하여 전달한다.
 * JWT 내부에 mbrId, redirectUri, returnClient, userType 등 민감 파라미터가 포함되며,
 * 기관 API Key로 서명되어 변조를 감지할 수 있다.
 *
 * <p>레거시 평문 파라미터 방식(redirect_uri, mbrId 등)은 FE Step1에서 직접 처리하며
 * 이 DTO는 신규 signed_request 방식 전용이다.
 *
 * @see io.github.hipstermin.idem.hub.kr.conversion.ConversionInitController
 * @see io.github.hipstermin.idem.hub.kr.conversion.ConversionInitService
 */
@Getter
@NoArgsConstructor
public class ConversionInitRequest {

    /**
     * 기관 서버가 HMAC-SHA256(HS256)으로 서명한 JWT
     *
     * <p>JWT 페이로드 구조:
     * <pre>
     * {
     *   "sub":         "BIZINFO_001",                              // 기관 코드
     *   "mbrId":       "BIZ_USER_001",                            // 기관 회원 ID
     *   "redirectUri": "https://www.bizinfo.go.kr/callback",      // 전환 완료 후 복귀 URL
     *   "returnClient":"sp-bizinfo",                               // OnePass client_id
     *   "userType":    "IND",                                      // ENT | IND | (생략 가능)
     *   "iat":         1716123456,                                 // 발급 시각 (epoch seconds)
     *   "exp":         1716123756,                                 // 만료 (5분 후)
     *   "jti":         "uuid-v4"                                   // 재사용 방지 nonce (선택)
     * }
     * </pre>
     */
    @NotBlank
    private String signedRequest;

    /**
     * 기관 코드 — JWT 서명 검증 시 agency_meta 조회 키로 사용
     *
     * <p>JWT sub claim과 일치해야 한다 (서명 검증 시 확인).
     * URL에 평문으로 노출되어도 무방 (서명 없이는 위조 불가).
     */
    @NotBlank
    private String agencyCode;
}
