import { useEffect, useMemo } from 'react';
import { useLocation } from 'react-router-dom';

/** 스크롤 시 섹션 애니메이션 적용 */
export function useSectionAnimation(): void {
	useEffect(() => {
		const sectionOn = (): void => {
			const offset = -window.innerHeight * 0.8;
			const scrollTop = window.scrollY;
			const sections = document.querySelectorAll<HTMLElement>('.main > *');

			sections.forEach((section) => {
				const top = section.getBoundingClientRect().top + window.scrollY;
				if (scrollTop > top + offset) {
					section.classList.add('show');
				}
			});
		};

		const applyDelay = (
			parentSelector: string,
			delayDiv: number,
			delayBase: number,
		): void => {
			document.querySelectorAll<HTMLElement>(parentSelector).forEach((parent) => {
				Array.from(parent.children).forEach((child, index) => {
					(child as HTMLElement).style.animationDelay = `${(index + 1) / delayDiv + delayBase}s`;
				});
			});
		};

		const applyDataText = (selector: string): void => {
			document.querySelectorAll<HTMLElement>(selector).forEach((el) => {
				el.setAttribute('data-text', el.textContent || '');
			});
		};

		applyDataText('.main .sec01 .inner .text-box h2');
		applyDelay('.main .sec01 .inner .list', 15, 0.1);
		applyDelay('.main .sec02 .tab-cont > div.active', 20, 0.1);

		sectionOn();
		window.addEventListener('scroll', sectionOn);
		return (): void => window.removeEventListener('scroll', sectionOn);
	}, []);
}

/**
 * return_uri에서 origin + 첫 번째 경로 세그먼트까지 추출
 * 예: https://www.smes.go.kr/mna-iam/iam/oauth/loginCallback.do
 *   → https://www.smes.go.kr/mna-iam/
 */
function extractHomeUrl(returnUri: string | null): string | null {
	if (!returnUri) return null;
	try {
		const url = new URL(returnUri);
		const firstSegment = url.pathname.split('/').filter(Boolean)[0];
		return firstSegment ? `${url.origin}/${firstSegment}/` : url.origin;
	} catch {
		return null;
	}
}

/** URL 쿼리 파라미터에서 Keycloak 로그인 파라미터를 추출 */
export function useKeycloakParams(): {
	actionUrl: string | null;
	error: string | null;
	code: string | null;
	returnUri: string | null;
} {
	const { search } = useLocation();

	return useMemo(() => {
		const params = new URLSearchParams(search);
		return {
			actionUrl: params.get('action_url'),
			error: params.get('error'),
			code: params.get('code'),
			returnUri: extractHomeUrl(params.get('return_uri')),
		};
	}, [search]);
}
