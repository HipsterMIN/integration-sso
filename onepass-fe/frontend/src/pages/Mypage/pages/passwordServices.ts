// /mypage-member/password 단계 간 상태 전달 — sessionStorage 기반
// Step1 인증 성공 → ciToken 저장 + AUTH_KEY 마킹 → Step2 진입 허용
// Step2 비밀번호 변경 성공 시 모두 폐기

const AUTH_KEY = 'mypage_password_step1_passed';
const CI_TOKEN_KEY = 'mypage_password_ci_token';

export function markAuthed(): void {
	sessionStorage.setItem(AUTH_KEY, '1');
}

export function isAuthed(): boolean {
	return sessionStorage.getItem(AUTH_KEY) === '1';
}

export function clearAuthed(): void {
	sessionStorage.removeItem(AUTH_KEY);
}

export function saveCiToken(token: string): void {
	sessionStorage.setItem(CI_TOKEN_KEY, token);
}

export function loadCiToken(): string {
	return sessionStorage.getItem(CI_TOKEN_KEY) || '';
}

export function clearCiToken(): void {
	sessionStorage.removeItem(CI_TOKEN_KEY);
}

export function clearAll(): void {
	clearAuthed();
	clearCiToken();
}
