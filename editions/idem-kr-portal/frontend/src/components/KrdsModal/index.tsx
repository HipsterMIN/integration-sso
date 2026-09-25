import { ReactNode, useCallback, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import cx from 'classnames';

interface ModalButton {
	label: string;
	variant: 'primary' | 'tertiary' | 'secondary';
	size?: 'medium' | 'large';
	full?: boolean;
	half?: boolean;
	onClick?: () => void;
}

interface ModalProps {
	id?: string;
	isOpen: boolean;
	onClose: () => void;
	topText: string;
	title: ReactNode;
	children: ReactNode;
	buttons: ModalButton[];
	contentsClassName?: string;
	size?: 'small' | 'medium';
}

function Modal({
	id,
	isOpen,
	onClose,
	topText,
	title,
	children,
	buttons,
	contentsClassName,
	size = 'medium',
}: ModalProps): JSX.Element {
	const dialogRef = useRef<HTMLDivElement>(null);
	const previousFocusRef = useRef<HTMLElement | null>(null);
	const titleId = id ? `${id}_title` : undefined;
	const descId = id ? `${id}_desc` : undefined;

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

				if (e.shiftKey && document.activeElement === first) {
					e.preventDefault();
					last.focus();
				} else if (!e.shiftKey && document.activeElement === last) {
					e.preventDefault();
					first.focus();
				}
			}
		},
		[isOpen, onClose],
	);

	useEffect(() => {
		if (isOpen) {
			previousFocusRef.current = document.activeElement as HTMLElement;
			document.body.style.overflow = 'hidden';
			setTimeout(() => dialogRef.current?.focus(), 0);
		} else {
			document.body.style.overflow = '';
			previousFocusRef.current?.focus();
		}
		return (): void => {
			document.body.style.overflow = '';
		};
	}, [isOpen]);

	useEffect(() => {
		document.addEventListener('keydown', handleKeyDown);
		return (): void => document.removeEventListener('keydown', handleKeyDown);
	}, [handleKeyDown]);

	return createPortal(
		<>
			<div
				id={id}
				className={cx('modal-wrap', { open: isOpen })}
				role="dialog"
				aria-modal="true"
				aria-labelledby={titleId}
				aria-describedby={descId}
				ref={dialogRef}
				tabIndex={-1}
			>
				<div className={cx('modal-dialog', { 'modal-dialog--small': size === 'small' })}>
					<div className="modal-content">
						<button type="button" className="btn-close" onClick={onClose} aria-label="닫기">
							&#10005;
						</button>
						<div className="modal-body">
							<div className="modal-header">
								<p className="modal-top-text">{topText}</p>
								<h2 className="modal-title" id={titleId}>
									{title}
								</h2>
							</div>
							<div className={cx('modal-conts', contentsClassName)} id={descId}>
								{children}
							</div>
						</div>
						<div className="modal-btn btn-box">
							{buttons.map((btn, idx) => (
								<button
									key={`${btn.variant}-${idx}`}
									type="button"
									className={cx('btn', btn.size || 'medium', btn.variant, 'close-modal', { full: btn.full, half: btn.half })}
									onClick={btn.onClick ?? onClose}
								>
									{btn.label}
								</button>
							))}
						</div>
					</div>
				</div>
				<div className="modal-back" onClick={onClose} aria-hidden="true" />
			</div>
			{isOpen && <div className="modal-bg" />}
		</>,
		document.body,
	);
}

export type { ModalButton, ModalProps };
export default Modal;
