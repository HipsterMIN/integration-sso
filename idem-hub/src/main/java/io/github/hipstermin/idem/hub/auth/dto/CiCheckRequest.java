package io.github.hipstermin.idem.hub.auth.dto;

import io.github.hipstermin.idem.hub.auth.validation.MemberDivisionCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * NICE 본인인증 CI 확인 요청 DTO (S9-T3: Bean Validation 적용)
 *
 * <p>NICE 휴대폰 본인인증 결과로 획득한 CI를 바탕으로 회원 조회를 요청한다.
 * 회원구분코드(mbrDvsnCd)에 따라 필수 파라미터가 달라진다.
 *
 * <p><b>API 경로:</b> {@code POST /api/v1/auth/nice/ci-check}
 *
 * <p><b>설계 원칙 (운영 수준 정책):</b>
 * 이 API는 CI 기반 기존 회원 조회 전용이다.
 * 신규 사용자 등록은 반드시 {@code POST /api/v1/auth/nice/phone/result} 흐름을 통해 완료되어야 하며,
 * 해당 흐름에서 name/birthday/gender/mobile 등 완전한 프로필이 Q-IM에 등록된다.
 * ci-check 시점에 Q-IM에 CI가 없으면 선행 인증 미완료로 간주하고 에러를 반환한다.
 *
 * <p><b>유효성 규칙:</b>
 * <ul>
 *   <li>{@code ci} — 필수. NICE에서 반환한 88자 연계정보</li>
 *   <li>{@code mbrDvsnCd} — 필수. A101(개인) 또는 A102(기업)</li>
 *   <li>{@code bizno} — A102(기업회원)일 때 필수 (서비스 레이어에서 검증)</li>
 * </ul>
 *
 * <p><b>Cross-Field 검증 (bizno 필수):</b>
 * mbrDvsnCd=A102일 때 bizno 필수 검증은 서비스 레이어({@code AuthService.checkNiceCi()})에서 수행.
 * Bean Validation의 {@code @AssertTrue} 또는 커스텀 Validator로도 구현 가능하나
 * 비즈니스 로직 집중 원칙에 따라 서비스 레이어에서 처리.
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
     * <p>NICE 휴대폰 본인인증 결과의 CI 필드 값. 주민등록번호를 SHA-512로 해시한 88자 문자열.
     * <b>PII 주의:</b> 이 필드는 서버 내부에서만 처리하고 로그에 전체 출력하지 말 것.
     */
    @NotBlank(message = "ci는 필수입니다")
    @Size(min = 8, max = 200, message = "ci는 8자~200자 사이여야 합니다")
    private String ci;

    /**
     * 회원구분코드
     *
     * <ul>
     *   <li>{@code A101} — 개인회원</li>
     *   <li>{@code A102} — 기업회원 (대표자 본인인증)</li>
     * </ul>
     */
    @NotBlank(message = "mbrDvsnCd는 필수입니다")
    @MemberDivisionCode // 허용 목록은 ido.qim.member-division-codes (S1 범용화)
    private String mbrDvsnCd;

    /**
     * 기업회원 아이디
     *
     * <p>mbrDvsnCd=A102일 때만 전달. 이미 가입된 기업회원과의 연결에 사용.
     */
    @Size(max = 100, message = "cmpMbrId는 100자를 초과할 수 없습니다")
    private String cmpMbrId;

    /**
     * 사업자등록번호 (10자리, 하이픈 없음)
     *
     * <p>mbrDvsnCd=A102(기업회원)일 때 필수. 예: "1234567890"
     */
    @Pattern(regexp = "^[0-9]{10}$|^$", message = "bizno는 10자리 숫자여야 합니다")
    private String bizno;

    /**
     * 개인회원 이름
     *
     * <p>mbrDvsnCd=A101일 때 선택적으로 전달. 회원 조회 보조용.
     */
    @Size(max = 50, message = "indvlMbrNm은 50자를 초과할 수 없습니다")
    private String indvlMbrNm;

    /**
     * 개인회원 아이디
     *
     * <p>mbrDvsnCd=A101일 때 선택적으로 전달. 이미 가입된 개인회원과의 연결에 사용.
     */
    @Size(max = 100, message = "indvlMbrId는 100자를 초과할 수 없습니다")
    private String indvlMbrId;
}
