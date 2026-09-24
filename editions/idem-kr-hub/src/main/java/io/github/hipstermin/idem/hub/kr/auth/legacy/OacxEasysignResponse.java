package io.github.hipstermin.idem.hub.kr.auth.legacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OACX 간편서명 콜백 처리 응답 DTO
 *
 * <p>OACX SDK 콜백 데이터를 복호화한 결과를 FE에 반환한다.
 *
 * <p><b>결과 코드 정의:</b>
 * <ul>
 *   <li>{@code 2000} — 성공</li>
 *   <li>{@code 4000} — fn 값이 "authComplete"가 아님</li>
 *   <li>{@code 4001} — OACX resultCode가 "200"이 아님 (인증 실패/취소)</li>
 *   <li>{@code 5002} — JWT 복호화 실패</li>
 * </ul>
 *
 * <p><b>FE 성공 응답 예시:</b>
 * <pre>
 * {
 *   "resultCode": "2000",
 *   "resultMsg": "성공",
 *   "name": "홍길동",
 *   "birthday": "19900101",
 *   "phone": "01012345678"
 * }
 * </pre>
 *
 * <p><b>보안 정책 (Q3=B):</b>
 * CI(연계정보)는 PII이므로 FE에 반환하지 않음.
 * OACX SDK 복호화 결과에 CI가 포함되어 있어도 이 DTO에서 의도적으로 제외.
 *
 * <p><b>OACX provider별 키 이름 차이 처리:</b>
 * <ul>
 *   <li>naver/toss/dream/banksalad: {@code name}, {@code phone} 키 사용</li>
 *   <li>pass(통신3사): {@code userNm}, {@code phoneNo} 키 사용</li>
 * </ul>
 * {@code AuthService}에서 통일 처리 후 이 DTO에 담아 반환.
 *
 * @see OacxEasysignRequest
 * @see OacxAccessInfoResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OacxEasysignResponse {

    /** 결과 코드 (2000: 성공, 4xxx: 클라이언트 오류, 5xxx: 서버 오류) */
    private String resultCode;

    /** 결과 메시지 */
    private String resultMsg;

    /**
     * CI (연계정보) — 설계 결정 Q3=B에 의해 FE에 미반환
     *
     * <p>이 필드는 {@code @JsonInclude(NON_NULL)}에 의해 null이면 JSON에서 제외됨.
     * 서비스 레이어에서 이 필드를 채우지 않으므로 항상 미반환.
     * 향후 IM API 연동 시 {@code AuthService} 내부에서 사용 예정 (S7-T6).
     */
    // CI는 FE 응답에 포함하지 않음 (Q3=B 결정)
    // 내부 처리용: AuthService 내부에서 decrypted.get("ci")로 접근 가능
    private String ci;

    /**
     * 이름
     *
     * <p>provider별 키 이름 차이를 AuthService에서 통일 처리 후 반환.
     */
    private String name;

    /**
     * 생년월일 (YYYYMMDD 형식. 예: "19900101")
     *
     * <p>provider에 따라 제공 여부가 다를 수 있음. 없으면 null.
     */
    private String birthday;

    /**
     * 휴대폰 번호 (하이픈 없음. 예: "01012345678")
     *
     * <p>provider별 키 이름 차이를 AuthService에서 통일 처리 후 반환.
     */
    private String phone;
}
