package kr.go.smes.qim.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 사용자 속성 (Q-IM 정본 — 기관에는 허용 속성만 Projection)
 * 설계서 10.2 / 16.9절 참조
 *
 * [P3-05] isMinor / guardianQimUserId / guardianConsentAt 추가
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
    /** 출생 연도 (일/월 제외) */
    private final Short birthYear;

    // ── P3-05: 미성년자 / 보호자 ──────────────────────────────────────────────

    /**
     * 14세 미만 여부 — {@link MinorGuardianPolicy#isMinor(Short)} 기준.
     * 보호자 동의 완료 전까지 일부 서비스 제한.
     */
    private final Boolean isMinor;

    /**
     * 보호자 qim_user_id — isMinor=true인 경우에만 설정됨.
     * null이면 아직 보호자가 지정되지 않은 상태.
     */
    private final String guardianQimUserId;

    /**
     * 보호자 동의 완료 시각 — null이면 아직 미동의.
     */
    private final LocalDateTime guardianConsentAt;

    /** 보호자 동의가 완료됐는지 여부 */
    public boolean isGuardianConsentDone() {
        return Boolean.TRUE.equals(isMinor) && guardianConsentAt != null;
    }
}
