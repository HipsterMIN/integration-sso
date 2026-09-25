/**
 * 만14세 미만 판정 유틸리티
 *
 * 정보통신망법 제31조: 만14세 미만 아동의 개인정보 수집 시 법정대리인 동의 필수
 * BE: MinorGuardianPolicy.isMinor(birthYear) 구현과 동일한 로직
 *
 * birthDate 포맷:
 *   - NICE 휴대폰인증: "YYYYMMDD" (result.birthdate)
 *   - 간편인증:        "YYYYMMDD" (result.birthday)
 */
export const MINOR_AGE_THRESHOLD = 14;

/**
 * birthDate(YYYYMMDD 또는 YYYY-MM-DD) 로부터 만14세 미만 여부를 판정한다.
 *
 * 판정 기준: 현재 연도 - 출생 연도 < 14
 * BE MinorGuardianPolicy.isMinor(birthYear)와 동일한 연도 기반 비교.
 *
 * @param birthDate - 생년월일 문자열 (YYYYMMDD 또는 YYYY-MM-DD)
 * @returns 만14세 미만이면 true
 */
export function isMinorByBirthDate(birthDate: string): boolean {
	if (!birthDate || birthDate.length < 4) return false;

	// "YYYYMMDD" → "YYYY" / "YYYY-MM-DD" → "YYYY"
	const yearStr = birthDate.replace(/-/g, '').slice(0, 4);
	const birthYear = parseInt(yearStr, 10);

	if (Number.isNaN(birthYear)) return false;

	const currentYear = new Date().getFullYear();
	return currentYear - birthYear < MINOR_AGE_THRESHOLD;
}
