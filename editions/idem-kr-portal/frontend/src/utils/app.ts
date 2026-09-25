import getLocalStorage from 'api/browser/localstorage/get';
import { SKIP_ONBOARDING } from 'constants/onboarding';

export const isOnboardingSkipped = (): boolean =>
	getLocalStorage(SKIP_ONBOARDING) === 'true';

export function extractDomain(email: string): string {
	const emailParts = email.split('@');
	if (emailParts.length !== 2) {
		return email;
	}
	return emailParts[1];
}

export const isCloudUser = (): boolean => {
	const { hostname } = window.location;

	// 2026.04.15 임시주석: 타 환경 배포를 위해 기준 도메인 체크 비활성화
	// return hostname?.endsWith('ucube.cloud');
	return false;
};

export const isEECloudUser = (): boolean => {
	const { hostname } = window.location;

	// 2026.04.15 임시주석: 타 환경 배포를 위해 기준 도메인 체크 비활성화
	// return hostname?.endsWith('ucube.kr');
	return false;
};

export const checkVersionState = (
	currentVersion: string,
	latestVersion: string,
): boolean => {
	const versionCore = currentVersion?.split('-')[0];
	return versionCore === latestVersion;
};

// list of forbidden tags to remove in dompurify
export const FORBID_DOM_PURIFY_TAGS = ['img', 'form'];
