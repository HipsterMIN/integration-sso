package kr.go.smes.qim.domain;

import java.time.Year;

/**
 * 14세 미만 보호자 인증 정책
 *
 * <p>개인정보보호법 제39조의3(아동의 개인정보보호)에 따라
 * 만 14세 미만 아동의 개인정보 수집·이용 시 법정대리인(보호자)의 동의가 필요합니다.
 *
 * <h3>판정 기준</h3>
 * <ul>
 *   <li>현재 연도 기준으로 {@code currentYear - birthYear < 14}이면 미성년자</li>
 *   <li>생년 정보가 없는 경우(null)는 미성년자로 판정하지 않음 (보수적 기본값)</li>
 * </ul>
 *
 * <p>설계서 §P3-05 참조
 */
public final class MinorGuardianPolicy {

    /** 보호자 동의 기준 나이 (만 14세 미만) */
    public static final int MINOR_AGE_THRESHOLD = 14;

    private MinorGuardianPolicy() { /* utility class */ }

    /**
     * 주어진 출생 연도를 기준으로 14세 미만 여부를 판정합니다.
     *
     * @param birthYear 출생 연도 (null이면 false 반환)
     * @return 14세 미만이면 {@code true}
     */
    public static boolean isMinor(Short birthYear) {
        if (birthYear == null) {
            return false;
        }
        int currentYear = Year.now().getValue();
        // 생년과 현재 연도 차이만으로 판정 (월/일 미보유)
        // 보수적: 같은 해에 태어났어도 당해 말까지는 14세 미만으로 판정
        return (currentYear - birthYear) < MINOR_AGE_THRESHOLD;
    }

    /**
     * 미성년자이고 아직 보호자 동의가 완료되지 않은 경우를 판정합니다.
     *
     * @param profile 사용자 프로필
     * @return 보호자 동의가 필요한 경우 {@code true}
     */
    public static boolean requiresGuardianConsent(UserProfile profile) {
        return Boolean.TRUE.equals(profile.getIsMinor())
                && !profile.isGuardianConsentDone();
    }
}
