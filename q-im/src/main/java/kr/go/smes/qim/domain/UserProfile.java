package kr.go.smes.qim.domain;

import lombok.Builder;
import lombok.Getter;

/**
 * 사용자 속성 (Q-IM 정본 — 기관에는 허용 속성만 Projection)
 * 설계서 10.2 / 16.9절 참조
 */
@Getter
@Builder
public class UserProfile {
    /** 마스킹된 이름 (예: 홍*동) — 기관 전달 시 필터링 적용 */
    private final String nameMasked;
    /** 마스킹된 휴대폰 번호 */
    private final String mobileMasked;
    /** 내/외국인 구분 */
    private final String nationalityType;
    /** CI (연계정보) — 기관별 허용 여부 정책 결정 */
    private final String ci;
    /** DI (중복가입확인정보) */
    private final String di;
}
