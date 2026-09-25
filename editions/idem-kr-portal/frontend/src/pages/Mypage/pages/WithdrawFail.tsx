import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import history from 'lib/history';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Redirect } from 'react-router-dom';

import { getMypageRoute } from './routes';
import { WITHDRAW_FAIL_LIST_KEY } from './WithdrawStep2';

function loadFailedInstNames(): string[] {
	try {
		const raw = sessionStorage.getItem(WITHDRAW_FAIL_LIST_KEY);
		if (!raw) return [];
		const parsed: unknown = JSON.parse(raw);
		if (!Array.isArray(parsed)) return [];
		return parsed.filter((v): v is string => typeof v === 'string');
	} catch {
		return [];
	}
}

// 실패 기관명 슬라이더 — 칩이 viewport 보다 많을 때 prev/next/dot 으로 좌우 슬라이드.
// 트랙은 scroll-snap(컬럼 단위) + 스크롤바 숨김. 한 step = `--cols-per-step` 컬럼.
// CSS 변수(`--cols-per-step`)로 데스크톱 4, 모바일 1 을 분기하므로 슬라이드/dot 좌표계가
// 항상 visible cols 와 동일해 인디케이터와 실제 위치가 일치한다.
const DEFAULT_COLS_PER_STEP = 4;

function FailedInstSlider({ items }: { items: string[] }): JSX.Element {
	const trackRef = useRef<HTMLUListElement>(null);
	const [atStart, setAtStart] = useState(true);
	const [atEnd, setAtEnd] = useState(false);
	const [page, setPage] = useState(0);
	const [pageCount, setPageCount] = useState(1);

	/** 한 페이지 step 폭 = cols-per-step 컬럼 × (칩 width + column-gap) */
	const getStepWidth = (el: HTMLElement): number => {
		const firstItem = el.querySelector<HTMLElement>('li');
		if (!firstItem) return 0;
		const cs = window.getComputedStyle(el);
		const gapPx = parseFloat(cs.columnGap || '0') || 0;
		const colsPerStep =
			parseInt(cs.getPropertyValue('--cols-per-step'), 10) ||
			DEFAULT_COLS_PER_STEP;
		return (firstItem.offsetWidth + gapPx) * colsPerStep;
	};

	const updateBounds = useCallback((): void => {
		const el = trackRef.current;
		if (!el) return;
		const { scrollLeft, clientWidth, scrollWidth } = el;
		const atStartNow = scrollLeft <= 1;
		const atEndNow = scrollLeft + clientWidth >= scrollWidth - 1;
		setAtStart(atStartNow);
		setAtEnd(atEndNow);

		const step = getStepWidth(el);
		if (step <= 0 || scrollWidth <= clientWidth + 1) {
			// 트랙 전체가 viewport 안에 들어가면 페이지 1개 (버튼/dot 숨김)
			setPageCount(1);
			setPage(0);
			return;
		}

		// step 단위(컬럼 단위)로 페이지 분할 — scroll-snap 결과(컬럼 left edge)와 동일 좌표계
		// 남는 컬럼이 step 미만이어도 한 페이지를 잡아야 하므로 count 는 ceil.
		// 1px 톨러런스 — maxScroll 이 step 의 정수배일 때 sub-pixel float 오차로
		// ceil 이 1 페이지를 더 세는 것 방지 (예: 8개에서 dot 3개 나오는 케이스)
		const maxScroll = scrollWidth - clientWidth;
		const count = Math.max(1, Math.ceil((maxScroll - 1) / step) + 1);
		const current = atEndNow
			? count - 1
			: Math.min(count - 1, Math.max(0, Math.round(scrollLeft / step)));
		setPageCount(count);
		setPage(current);
	}, []);

	useEffect(() => {
		updateBounds();
		const el = trackRef.current;
		if (!el || typeof ResizeObserver === 'undefined') return undefined;
		const ro = new ResizeObserver(updateBounds);
		ro.observe(el);
		return (): void => ro.disconnect();
	}, [items, updateBounds]);

	const slide = useCallback((direction: 1 | -1): void => {
		const el = trackRef.current;
		if (!el) return;
		const step = getStepWidth(el);
		if (step <= 0) return;
		el.scrollBy({ left: direction * step, behavior: 'smooth' });
	}, []);

	const goToPage = useCallback((target: number): void => {
		const el = trackRef.current;
		if (!el) return;
		const step = getStepWidth(el);
		if (step <= 0) return;
		const maxScroll = el.scrollWidth - el.clientWidth;
		// 마지막 페이지는 round 손실로 maxScroll 을 못 채워 onScroll 페이지 계산이
		// 한 칸 모자라게 나오는 케이스가 있어 clamp.
		const left = Math.min(target * step, maxScroll);
		el.scrollTo({ left, behavior: 'smooth' });
	}, []);

	const hasMultiplePages = pageCount > 1;

	return (
		<div
			className="withdraw-fail-slider"
			role="region"
			aria-label="탈퇴 실패 기관 슬라이드"
		>
			{hasMultiplePages && (
				<button
					type="button"
					className="slider-btn prev"
					onClick={(): void => slide(-1)}
					disabled={atStart}
					aria-label="이전 기관 보기"
				>
					<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
				</button>
			)}
			<ul
				ref={trackRef}
				className="withdraw-fail-list"
				aria-label="탈퇴 실패 기관 목록"
				onScroll={updateBounds}
			>
				{items.map((instNm) => (
					<li key={instNm}>
						<span>{instNm}</span>
					</li>
				))}
			</ul>
			{hasMultiplePages && (
				<button
					type="button"
					className="slider-btn next"
					onClick={(): void => slide(1)}
					disabled={atEnd}
					aria-label="다음 기관 보기"
				>
					<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
				</button>
			)}
			{hasMultiplePages && (
				<ol
					className="slider-dots"
					role="tablist"
					aria-label="슬라이드 페이지"
				>
					{Array.from({ length: pageCount }, (_, i) => (
						<li key={i}>
							<button
								type="button"
								role="tab"
								aria-selected={i === page}
								aria-label={`${i + 1}페이지로 이동`}
								className={`slider-dot${i === page ? ' active' : ''}`}
								onClick={(): void => goToPage(i)}
							/>
						</li>
					))}
				</ol>
			)}
		</div>
	);
}

// 회원 탈퇴 API 실패 화면 — /mypage-{member|business}/withdraw/fail
// WithdrawStep2 에서 탈퇴 API 가 실패했을 때 진입.
// 직접 진입 차단을 위해 step2 에서 sessionStorage 플래그를 세팅해야만 노출.
function WithdrawFail(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const withdrawRoute = getMypageRoute(memberType, 'WITHDRAW');

	if (sessionStorage.getItem('mypage_withdraw_fail_passed') !== '1') {
		return <Redirect to={withdrawRoute} />;
	}

	const failedInstNames = loadFailedInstNames();

	const handleRetry = (): void => {
		sessionStorage.removeItem('mypage_withdraw_fail_passed');
		sessionStorage.removeItem('mypage_withdraw_step1_passed');
		sessionStorage.removeItem(WITHDRAW_FAIL_LIST_KEY);
		history.push(withdrawRoute);
	};

	return (
		<MypageContent>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>일시적인 오류로 탈퇴 처리가 완료되지 않았습니다.</p>
						</li>
						<li>
							<p>잠시 후 다시 시도해 주시기 바랍니다.</p>
						</li>
					</ul>
					<figure className="img">
						<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
					</figure>
				</div>
			</div>
			<div className="form-container" aria-label="회원 탈퇴 실패">
				<div className="white-wrap completed">
					<figure className="img">
						<img
							src={IMAGES.RENEWAL_WRITE_COMPLETED_IMG_2}
							alt=""
							aria-hidden="true"
						/>
					</figure>
					<h4 className="completed-tit">
						{failedInstNames.length > 0 ? (
							<>
								{isBusiness ? '기업회원' : '통합회원'} 탈퇴 처리에 실패한
								기업이 {failedInstNames.length}건 있습니다
							</>
						) : (
							<>
								{isBusiness ? '기업회원' : '통합회원'} 탈퇴 처리에
								실패하였습니다
							</>
						)}
					</h4>
					{failedInstNames.length > 0 && (
						<FailedInstSlider items={failedInstNames} />
					)}
					<p className="completed-txt gray">
						문제가 지속되면 고객센터로 문의해 주시기 바랍니다.
					</p>
				</div>
				<div className="btn-box" role="group" aria-label="페이지 이동">
					<button type="button" className="btn point" onClick={handleRetry}>
						<span>다시 시도하기</span>
						<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
					</button>
				</div>
			</div>
		</MypageContent>
	);
}

export default WithdrawFail;
