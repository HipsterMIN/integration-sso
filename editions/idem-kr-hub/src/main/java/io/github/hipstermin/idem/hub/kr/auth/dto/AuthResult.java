package io.github.hipstermin.idem.hub.kr.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 본인인증 결과 통합 DTO
 *
 * <p>NICE 휴대폰 인증 또는 OACX 간편서명 인증 결과를 담는 내부 데이터 전달 객체.
 * {@link io.github.hipstermin.idem.hub.kr.auth.port.ImApiOutPort#register}를 통해 Q-IM에 CI/개인정보를 등록할 때 사용한다.
 *
 * <p><b>보안 원칙 (Q3=B):</b>
 * CI는 PII(개인식별정보)이므로 절대 FE 응답 DTO에 포함하지 않는다.
 * 이 DTO는 백엔드 내부(Q-IM API 전달) 전용으로만 사용한다.
 *
 * <p><b>S7-T6 구현 완료:</b>
 * <ul>
 *   <li>OACX 간편서명 완료 후 → {@link io.github.hipstermin.idem.hub.kr.auth.service.AuthService#handleOacxEasysign} 에서 Q-IM 등록</li>
 *   <li>NICE 휴대폰 인증 완료 후 → {@link io.github.hipstermin.idem.hub.kr.auth.service.NiceAuthService#getNicePhoneAuthResult} 에서 Q-IM 등록</li>
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
