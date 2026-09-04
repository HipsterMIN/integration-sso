package kr.go.smes.ido.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * NICE 휴대폰 본인인증 결과 응답 DTO
 *
 * <p>NICE 인증 결과를 복호화한 후 FE에 반환하는 응답.
 * CI(연계정보)는 Q3=B 결정에 의해 FE에 미반환.
 *
 * <p><b>결과 코드 정의:</b>
 * <ul>
 *   <li>{@code 2000} — 성공</li>
 *   <li>{@code 4000} — 요청 파라미터 오류 (request_no 누락, 세션 없음)</li>
 *   <li>{@code 5000} — 내부 처리 오류</li>
 *   <li>{@code 5002} — NICE 결과 조회 실패</li>
 *   <li>{@code 5003} — 데이터 무결성 검증 실패 (HMAC 불일치)</li>
 * </ul>
 *
 * <p><b>FE 성공 응답 예시:</b>
 * <pre>
 * {
 *   "resultCode": "2000",
 *   "resultMsg": "성공",
 *   "resultData": {
 *     "name": "홍길동",
 *     "birthdate": "19900101",
 *     "gender": "M",
 *     "nationalInfo": "0",
 *     "di": "ABCDEF...(64자)",
 *     "mobileCo": "SKT",
 *     "mobileNo": "01012345678"
 *   }
 * }
 * </pre>
 *
 * <p><b>보안 정책 (Q3=B):</b>
 * CI(연계정보)는 개인식별정보(PII)이므로 FE에 반환하지 않음.
 * CI는 {@code NiceAuthService.getNicePhoneAuthResult()} 내부에서 처리하며
 * FE 응답 DTO인 이 클래스에는 {@code ci} 필드가 없음.
 *
 * @see NicePhoneAuthResultRequest
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NicePhoneAuthResultResponse {

    /** 결과 코드 (2000: 성공, 4xxx: 클라이언트 오류, 5xxx: 서버 오류) */
    private String resultCode;

    /** 결과 메시지 */
    private String resultMsg;

    /** 인증 결과 상세 데이터 (성공 시에만 포함) */
    private ResultData resultData;

    /**
     * NICE 휴대폰 인증 결과 상세 데이터
     *
     * <p><b>CI 미포함 이유 (Q3=B):</b>
     * CI는 개인식별정보(PII)로 FE 브라우저에 노출 시 XSS 등 취약점으로
     * 개인정보 유출 위험이 있음. CI는 서버 내부에서만 처리.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResultData {
        /** 이름 */
        private String name;

        /** 생년월일 (YYYYMMDD 형식. 예: "19900101") */
        private String birthdate;

        /**
         * 성별
         * <ul>
         *   <li>{@code "0"} — 여성</li>
         *   <li>{@code "1"} — 남성</li>
         * </ul>
         */
        private String gender;

        /**
         * 내/외국인 구분
         * <ul>
         *   <li>{@code "0"} — 내국인</li>
         *   <li>{@code "1"} — 외국인</li>
         * </ul>
         */
        private String nationalInfo;

        /**
         * CI (연계정보) — 주민등록번호 기반 SHA-512 해시 (88자)
         *
         * <p><b>설계 결정 Q3=B: FE 미반환</b>
         * 이 필드는 FE 응답에 포함되지 않음. CI는 PII(개인식별정보)이므로 서버 내부에서만 처리.
         *
         * <p><b>S7-T6 구현 완료:</b>
         * CI는 {@link kr.go.smes.ido.auth.service.NiceAuthService#getNicePhoneAuthResult}에서
         * 복호화 후 {@link kr.go.smes.ido.auth.port.ImApiOutPort#register}를 통해 Q-IM에 등록됨.
         * FE 응답 DTO인 이 클래스에는 ci 필드가 없으므로 FE에 자연스럽게 미반환.
         */
        // CI는 FE 응답에 포함하지 않음 (Q3=B 결정) — ci 필드 의도적으로 미선언
        // private String ci;  ← Q3=B: 필드 자체를 제거하여 실수로 포함 방지

        /**
         * DI (중복가입확인정보) — 사이트별 고유 식별자 (64자)
         *
         * <p>CI와 달리 사이트 내에서만 유효한 식별자로, FE에 반환 가능.
         * 동일 사이트 내 중복 가입 방지에 사용.
         */
        private String di;

        /**
         * 통신사 코드
         * <ul>
         *   <li>{@code "SKT"} — SK텔레콤</li>
         *   <li>{@code "KT"}  — KT</li>
         *   <li>{@code "LGU+"} — LG유플러스</li>
         *   <li>{@code "SKT알뜰폰"} — SKT MVNO</li>
         *   <li>{@code "KT알뜰폰"} — KT MVNO</li>
         *   <li>{@code "LGU+알뜰폰"} — LGU+ MVNO</li>
         * </ul>
         */
        private String mobileCo;

        /** 휴대폰 번호 (하이픈 없음. 예: "01012345678") */
        private String mobileNo;
    }
}
