// FindPassword 단계 간 상태 전달 — sessionStorage 기반
// Step1 인증 성공 → ciToken 저장 + AUTH_KEY 마킹 → Step2 진입 허용
// Step2 비밀번호 저장 성공 → DONE_KEY 마킹 → Step3 진입 허용

const AUTH_KEY = 'find_password_authed';
const DONE_KEY = 'find_password_done';
const CI_TOKEN_KEY = 'find_password_ci_token';

export function markAuthed(): void {
	sessionStorage.setItem(AUTH_KEY, '1');
}

export function isAuthed(): boolean {
	return sessionStorage.getItem(AUTH_KEY) === '1';
}

export function clearAuthed(): void {
	sessionStorage.removeItem(AUTH_KEY);
}

export function markDone(): void {
	sessionStorage.setItem(DONE_KEY, '1');
}

export function isDone(): boolean {
	return sessionStorage.getItem(DONE_KEY) === '1';
}

export function clearDone(): void {
	sessionStorage.removeItem(DONE_KEY);
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
	clearDone();
	clearCiToken();
}
