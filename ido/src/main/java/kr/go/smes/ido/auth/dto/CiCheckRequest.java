package kr.go.smes.ido.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * NICE 본인인증 CI 확인 요청 DTO
 *
 * <p>NICE 휴대폰 본인인증 결과로 획득한 CI를 바탕으로 회원 조회/등록을 요청한다.
 * 회원구분코드(mbrDvsnCd)에 따라 필수 파라미터가 달라진다.
 *
 * <p><b>API 경로:</b> {@code POST /api/v1/auth/nice/ci-check}
 *
 * <p><b>FE 요청 예시 (개인회원 A101):</b>
 * <pre>
 * {
 *   "ci": "ABCDEF...(88자)",
 *   "mbrDvsnCd": "A101",
 *   "indvlMbrNm": "홍길동",
 *   "indvlMbrId": "honggildong"
 * }
 * </pre>
 *
 * <p><b>FE 요청 예시 (기업회원 A102):</b>
 * <pre>
 * {
 *   "ci": "ABCDEF...(88자)",
 *   "mbrDvsnCd": "A102",
 *   "cmpMbrId": "company123",
 *   "bizno": "1234567890"
 * }
 * </pre>
 *
 * <p><b>유효성 규칙:</b>
 * <ul>
 *   <li>{@code ci} — 필수. NICE에서 반환한 88자 연계정보</li>
 *   <li>{@code mbrDvsnCd} — 필수. A101(개인) 또는 A102(기업)</li>
 *   <li>{@code bizno} — A102(기업회원)일 때 필수</li>
 * </ul>
 *
 * @see CiCheckResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CiCheckRequest {

    /**
     * NICE 인증 결과 CI (연계정보)
     *
     * <p>NICE 휴대폰 본인인증 결과({@code POST /api/v1/auth/nice/phone/result})의
     * {@code resultData.ci} 필드 값. 주민등록번호를 SHA-512로 해시한 88자 문자열.
     * <b>PII 주의:</b> 이 필드는 서버 내부에서만 처리하고 로그에 전체 출력하지 말 것.
     */
    private String ci;

    /**
     * 회원구분코드
     *
     * <ul>
     *   <li>{@code A101} — 개인회원</li>
     *   <li>{@code A102} — 기업회원 (대표자 본인인증)</li>
     * </ul>
     */
    private String mbrDvsnCd;

    /**
     * 기업회원 아이디
     *
     * <p>mbrDvsnCd=A102일 때만 전달. 이미 가입된 기업회원과의 연결에 사용.
     */
    private String cmpMbrId;

    /**
     * 사업자등록번호 (10자리, 하이픈 없음)
     *
     * <p>mbrDvsnCd=A102(기업회원)일 때 필수. 예: "1234567890"
     */
    private String bizno;

    /**
     * 개인회원 이름
     *
     * <p>mbrDvsnCd=A101일 때 선택적으로 전달. 회원 조회 보조용.
     */
    private String indvlMbrNm;

    /**
     * 개인회원 아이디
     *
     * <p>mbrDvsnCd=A101일 때 선택적으로 전달. 이미 가입된 개인회원과의 연결에 사용.
     */
    private String indvlMbrId;
}
