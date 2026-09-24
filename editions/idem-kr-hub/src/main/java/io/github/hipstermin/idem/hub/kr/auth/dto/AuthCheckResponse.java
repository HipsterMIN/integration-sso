package io.github.hipstermin.idem.hub.kr.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 통합인증 서버 auth-check API 응답 DTO
 *
 * <p>통합인증 서버({@code POST /auth-check/v1})의 원본 응답을 매핑한다.
 * {@code resultData}는 JSON 직렬화 후 Base64 인코딩된 문자열로 내려오며,
 * 디코딩 및 {@link ResultData} 매핑은 {@code AuthService} 서비스 레이어에서 수행한다.
 *
 * <p><b>내부 처리 전용</b> — FE에 직접 노출되지 않음.
 * FE 반환 DTO는 {@link AuthCallbackResponse} 사용.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthCheckResponse {

    /** 통합인증 서버 결과 코드 */
    private String resultCode;

    /** 통합인증 서버 결과 메시지 */
    private String resultMsg;

    /**
     * Base64 인코딩된 결과 데이터
     *
     * <p>통합인증 서버는 {@link ResultData}를 JSON 직렬화 후 Base64 인코딩하여 전달함.
     * {@code AuthService.decodeResultData()}에서 디코딩 처리.
     */
    private String resultData;

    /**
     * Base64 디코딩 후 매핑되는 실제 결과 데이터
     *
     * <p>통합인증 서버의 기업 인증 상세 결과. 내부 처리 전용.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultData {
        /** 기업 대표자명 */
        private String name;

        /** 사업자등록번호 */
        private String businessNumber;

        /** 생년월일 */
        private String birth;

        /** 연락처 */
        private String phone;

        /** 개업일자 */
        private String bizOpendt;

        /** 기업 상태 */
        private String bizSts;

        /** 인증 정보 */
        private String certInfo;

        /** 영상통화 랜덤값 */
        private String vidRandom;

        /** 전자서명 데이터 목록 */
        private List<SignedData> signedDataLst;
    }

    /**
     * 전자서명 데이터 항목
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SignedData {
        /** 서명 대상 원문 */
        private String signTarget;

        /** 전자서명 데이터 */
        private String signData;

        /** 서명 시각 */
        private String signingTime;
    }
}
