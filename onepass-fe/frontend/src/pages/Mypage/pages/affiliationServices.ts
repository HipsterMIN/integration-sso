const SELECTED_KEY = 'affiliation_selected';
const CI_TOKEN_KEY = 'affiliation_ci_token';

/** 탈퇴 선택된 서비스 ID 목록 (페이지 간 전달용) */
export function saveSelectedServices(ids: string[]): void {
	sessionStorage.setItem(SELECTED_KEY, JSON.stringify(ids));
}

export function loadSelectedServices(): string[] {
	try {
		const raw = sessionStorage.getItem(SELECTED_KEY);
		if (raw) return JSON.parse(raw);
	} catch {
		// ignore
	}
	return [];
}

/** CI 토큰 저장/로드 (인증 Step → 탈퇴 Step 간 전달용) */
export function saveCiToken(token: string): void {
	sessionStorage.setItem(CI_TOKEN_KEY, token);
}

export function loadCiToken(): string {
	return sessionStorage.getItem(CI_TOKEN_KEY) || '';
}

export function clearCiToken(): void {
	sessionStorage.removeItem(CI_TOKEN_KEY);
}
