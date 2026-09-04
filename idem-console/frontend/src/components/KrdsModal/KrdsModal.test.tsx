import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import Modal from './index';

describe('KrdsModal', () => {
	const defaultProps = {
		id: 'test-modal',
		isOpen: true,
		onClose: jest.fn(),
		topText: '안내',
		title: '테스트 모달',
		buttons: [{ label: '확인', variant: 'primary' as const }],
		children: <p>모달 내용입니다.</p>,
	};

	beforeEach(() => {
		jest.clearAllMocks();
	});

	it('should render modal content when open', () => {
		render(<Modal {...defaultProps} />);

		expect(screen.getByRole('dialog')).toBeInTheDocument();
		expect(screen.getByText('안내')).toBeInTheDocument();
		expect(screen.getByText('테스트 모달')).toBeInTheDocument();
		expect(screen.getByText('모달 내용입니다.')).toBeInTheDocument();
	});

	it('should have correct aria attributes', () => {
		render(<Modal {...defaultProps} />);

		const dialog = screen.getByRole('dialog');
		expect(dialog).toHaveAttribute('aria-modal', 'true');
		expect(dialog).toHaveAttribute('aria-labelledby', 'test-modal_title');
		expect(dialog).toHaveAttribute('aria-describedby', 'test-modal_desc');
	});

	it('should have open class when isOpen is true', () => {
		render(<Modal {...defaultProps} />);

		const dialog = screen.getByRole('dialog');
		expect(dialog).toHaveClass('open');
	});

	it('should not have open class when isOpen is false', () => {
		render(<Modal {...defaultProps} isOpen={false} />);

		const dialog = screen.getByRole('dialog');
		expect(dialog).not.toHaveClass('open');
	});

	it('should call onClose when Escape key is pressed', async () => {
		render(<Modal {...defaultProps} />);

		await userEvent.keyboard('{Escape}');
		expect(defaultProps.onClose).toHaveBeenCalledTimes(1);
	});

	it('should call onClose when backdrop is clicked', async () => {
		render(<Modal {...defaultProps} />);

		const backdrop = screen.getByRole('dialog').querySelector('.modal-back');
		if (backdrop) await userEvent.click(backdrop);
		expect(defaultProps.onClose).toHaveBeenCalledTimes(1);
	});

	it('should use onClose as fallback when button onClick is not provided', async () => {
		render(<Modal {...defaultProps} />);

		await userEvent.click(screen.getByText('확인'));
		expect(defaultProps.onClose).toHaveBeenCalledTimes(1);
	});

	it('should use button onClick when provided', async () => {
		const customClick = jest.fn();
		render(
			<Modal
				{...defaultProps}
				buttons={[{ label: '확인', variant: 'primary', onClick: customClick }]}
			/>,
		);

		await userEvent.click(screen.getByText('확인'));
		expect(customClick).toHaveBeenCalledTimes(1);
		expect(defaultProps.onClose).not.toHaveBeenCalled();
	});

	it('should render multiple buttons', () => {
		render(
			<Modal
				{...defaultProps}
				buttons={[
					{ label: '취소', variant: 'tertiary' },
					{ label: '확인', variant: 'primary' },
				]}
			/>,
		);

		expect(screen.getByText('취소')).toBeInTheDocument();
		expect(screen.getByText('확인')).toBeInTheDocument();
	});

	it('should lock body scroll when open', () => {
		render(<Modal {...defaultProps} />);
		expect(document.body.style.overflow).toBe('hidden');
	});

	it('should restore body scroll when closed', () => {
		const { rerender } = render(<Modal {...defaultProps} />);
		rerender(<Modal {...defaultProps} isOpen={false} />);
		expect(document.body.style.overflow).toBe('');
	});
});
