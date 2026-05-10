package kr.go.smes.ido.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 본인인증 결과 통합 DTO
 *
 * <p>NICE 휴대폰 인증 또는 OACX 간편서명 인증 결과를 담는 내부 데이터 전달 객체.
 * 향후 IM API(Identity Management)에 CI/개인정보를 등록할 때 사용할 예정.
 *
 * <p><b>현재 상태:</b> ImApiOutPort.register() 구현 시 이 DTO를 전달 예정.
 * CI는 PII(개인식별정보)이므로 절대 FE 응답에 포함하지 말 것.
 *
 * <p><b>사용 예정:</b>
 * <ul>
 *   <li>OACX 간편서명 완료 후 → IM API 사용자 등록/매칭 (TODO: S7-T6)</li>
 *   <li>NICE 휴대폰 인증 완료 후 → ci-check 내부 호출로 CI 처리</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResult {

    /**
     * CI (연계정보) — 주민등록번호 기반 고유 식별자 (88자)
     *
     * <p><b>PII 주의:</b> 이 필드는 절대 FE 응답 DTO에 포함하지 않을 것.
     * 백엔드 내부(IM API 전달)에서만 사용.
     */
    private String ci;

    /**
     * DI (중복가입확인정보) — 사이트별 고유 식별자 (64자)
     */
    private String di;

    /** 이름 */
    private String name;

    /** 생년월일 (YYYYMMDD 형식) */
    private String birthday;

    /** 성별 (M: 남성, F: 여성) */
    private String gender;

    /** 휴대폰 번호 (하이픈 없음, 예: 01012345678) */
    private String mobile;

    /** 통신사 (SKT, KT, LGU+, SKT알뜰폰, KT알뜰폰, LGU+알뜰폰) */
    private String mobileCorp;
}
