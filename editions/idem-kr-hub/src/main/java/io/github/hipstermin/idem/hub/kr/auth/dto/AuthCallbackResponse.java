package io.github.hipstermin.idem.hub.kr.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 기업 간편인증 콜백 응답 DTO
 *
 * <p>통합인증 서버로부터 수신한 기업 인증 결과를 FE에 반환한다.
 * CI(연계정보)는 보안상 FE에 반환하지 않으며, IM API 전달용으로만 사용된다.
 *
 * <p><b>결과 코드 정의:</b>
 * <ul>
 *   <li>{@code 2000} — 성공</li>
 *   <li>{@code 5000} — 내부 처리 오류</li>
 *   <li>{@code 5001} — 통합인증 서버 응답 없음</li>
 * </ul>
 *
 * <p><b>주의:</b> {@code resultData}는 null일 수 있음 (인증 실패 시).
 * FE에서는 {@code resultCode === '2000'} 여부를 먼저 확인할 것.
 *
 * @see AuthCallbackRequest
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthCallbackResponse {

    /** 결과 코드 (2000: 성공, 5000/5001: 오류) */
    private String resultCode;

    /** 결과 메시지 */
    private String resultMsg;

    /** 인증 결과 데이터 (성공 시에만 포함) */
    private ResultData resultData;

    /**
     * 기업인증 결과 데이터
     *
     * <p><b>보안 정책:</b> CI(연계정보)는 개인식별정보(PII)이므로 FE에 반환하지 않음.
     * CI 저장은 향후 ImApiOutPort.register() 구현 시 백엔드 내부에서 처리.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResultData {
        /** 기업 대표자명 또는 담당자명 */
        private String name;

        /** 사업자등록번호 */
        private String businessNumber;

        /** 대표자 생년월일 (YYYYMMDD 형식) */
        private String birth;

        /** 대표자 연락처 */
        private String phone;

        /** 개업일자 (YYYYMMDD 형식) */
        private String bizOpendt;
    }
}
