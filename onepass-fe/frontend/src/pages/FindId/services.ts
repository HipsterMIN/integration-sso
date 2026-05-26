const FOUND_ID_KEY = 'find_id_login_id';
const LOGIN_RETURN_SEARCH_KEY = 'find_id_login_return_search';

export function saveFoundLoginId(id: string): void {
	sessionStorage.setItem(FOUND_ID_KEY, id);
}

export function loadFoundLoginId(): string {
	return sessionStorage.getItem(FOUND_ID_KEY) || '';
}

export function clearFoundLoginId(): void {
	sessionStorage.removeItem(FOUND_ID_KEY);
}

// Keycloak 컨텍스트(action_url, return_uri, return_client 등)를 find-id 진입 직전에
// 보존해 두었다가 result → login 복귀 시점에 다시 부착하기 위한 헬퍼.
export function saveLoginReturnSearch(search: string): void {
	sessionStorage.setItem(LOGIN_RETURN_SEARCH_KEY, search);
}

export function loadLoginReturnSearch(): string {
	return sessionStorage.getItem(LOGIN_RETURN_SEARCH_KEY) || '';
}

export function clearLoginReturnSearch(): void {
	sessionStorage.removeItem(LOGIN_RETURN_SEARCH_KEY);
}
