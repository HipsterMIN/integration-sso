const SELECTED_KEY = 'affiliation_selected';

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
