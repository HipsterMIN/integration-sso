/**
 * Any-ID 정부 통합로그인 모달
 *
 * SDK가 렌더링할 두 DOM 컨테이너를 포함한다:
 *   #anyidtoggle — 정부 통합로그인 토글 버튼 영역
 *   #anyidc      — 인증수단 카드 목록 (AnyidC.LOAD_MODULE() 이 채움)
 *
 * 사용 패턴:
 * <AnyIdLoginModal
 *   isOpen={anyIdModal}
 *   onClose={closeAnyIdModal}
 *   onInit={initAnyIdSdk}
 * />
 *
 * @see useAnyIdAuth
 */

import './AnyIdLoginModal.scss';

import { useCallback, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';

interface AnyIdLoginModalProps {
	isOpen: boolean;
	onClose: () => void;
	/** 모달 컨테이너가 DOM에 마운트된 후 SDK를 초기화하는 콜백 */
	onInit: () => void;
}

function AnyIdLoginModal({
	isOpen,
	onClose,
	onInit,
}: AnyIdLoginModalProps): JSX.Element | null {
	const dialogRef = useRef<HTMLDivElement>(null);
	const previousFocusRef = useRef<HTMLElement | null>(null);

	// ── 포커스 트랩 ─────────────────────────────────────────────────────────

	const handleKeyDown = useCallback(
		(e: KeyboardEvent) => {
			if (!isOpen || !dialogRef.current) return;

			if (e.key === 'Escape') {
				onClose();
				return;
			}

			if (e.key === 'Tab') {
				const focusable = dialogRef.current.querySelectorAll<HTMLElement>(
					'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
				);
				const first = focusable[0];
				const last = focusable[focusable.length - 1];

				if (!first) return;

				if (e.shiftKey && document.activeElement === first) {
					e.preventDefault();
					last?.focus();
				} else if (!e.shiftKey && document.activeElement === last) {
					e.preventDefault();
					first.focus();
				}
			}
		},
		[isOpen, onClose],
	);

	// ── 모달 열림/닫힘 부수효과 ──────────────────────────────────────────────

	useEffect(() => {
		if (isOpen) {
			previousFocusRef.current = document.activeElement as HTMLElement;
			document.body.style.overflow = 'hidden';

			// DOM 렌더링 완료 후 SDK 초기화 (한 프레임 대기)
			const raf = requestAnimationFrame(() => {
				onInit();
				// 닫기 버튼에 초기 포커스
				const closeBtn = dialogRef.current?.querySelector<HTMLButtonElement>(
					'.anyid-modal__close-btn',
				);
				closeBtn?.focus();
			});

			return (): void => {
				cancelAnimationFrame(raf);
			};
		} else {
			document.body.style.overflow = '';
			previousFocusRef.current?.focus();
		}

		return (): void => {
			document.body.style.overflow = '';
		};
	}, [isOpen, onInit]);

	useEffect(() => {
		document.addEventListener('keydown', handleKeyDown);
		return (): void => document.removeEventListener('keydown', handleKeyDown);
	}, [handleKeyDown]);

	// ── 배경 클릭으로 닫기 ──────────────────────────────────────────────────

	const handleBackdropClick = useCallback(
		(e: React.MouseEvent<HTMLDivElement>) => {
			if (e.target === e.currentTarget) onClose();
		},
		[onClose],
	);

	if (!isOpen) return null;

	return createPortal(
		<div
			className="anyid-modal__backdrop"
			role="dialog"
			aria-modal="true"
			aria-label="Any-ID 정부 통합로그인"
			onClick={handleBackdropClick}
		>
			<div
				className="anyid-modal__dialog"
				ref={dialogRef}
				tabIndex={-1}
			>
				{/* ── 헤더 ── */}
				<div className="anyid-modal__header">
					<div className="anyid-modal__header-text">
						<p className="anyid-modal__top-label">정부 통합인증</p>
						<h2 className="anyid-modal__title">로그인 방식을 선택해주세요.</h2>
						<p className="anyid-modal__desc">
							정부 통합로그인은 한 번의 로그인으로 연계된 모든 공공 웹서비스를 이용할 수
							있는 인증 서비스입니다.
						</p>
					</div>
					<button
						type="button"
						className="anyid-modal__close-btn"
						aria-label="Any-ID 로그인 모달 닫기"
						onClick={onClose}
					>
						&#10005;
					</button>
				</div>

				{/* ── SDK 렌더링 영역 ── */}
				<div className="anyid-modal__body">
					{/*
					 * 정부 통합로그인 토글 버튼
					 * AnyidC.LOAD_MODULE({ toggle: true }) 시 이 div에 렌더링됨
					 */}
					<div id="anyidtoggle" className="anyid-modal__toggle-wrap" />

					{/*
					 * 인증수단 카드 목록
					 * AnyidC.LOAD_MODULE() 이 config.anyidc.json을 읽어 카드 UI를 생성함
					 * Q-Net 처럼 모바일신분증/간편인증/공동인증서/금융인증서 카드가 표시됨
					 */}
					<div id="anyidc" className="anyid-modal__module-wrap" />
				</div>
			</div>
		</div>,
		document.body,
	);
}

export default AnyIdLoginModal;
