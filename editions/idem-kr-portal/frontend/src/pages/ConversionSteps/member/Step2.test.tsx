import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import ConversionStep2 from './Step2';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionStep2', () => {
	it('should render all agreement items', () => {
		render(<ConversionStep2 />);

		expect(screen.getByText('서비스 이용 약관')).toBeInTheDocument();
		expect(screen.getByText('개인정보 수집·이용에 동의합니다')).toBeInTheDocument();
		expect(screen.getByText('개인정보 제3자 제공에 동의합니다')).toBeInTheDocument();
	});

	it('should check all when "모두 동의" is clicked', async () => {
		render(<ConversionStep2 />);

		const allCheckbox = screen.getByRole('checkbox', { name: /모두 동의/ });
		await userEvent.click(allCheckbox);

		const checkboxes = screen.getAllByRole('checkbox');
		checkboxes.forEach((cb) => {
			expect(cb).toBeChecked();
		});
	});

	it('should uncheck all when "모두 동의" is clicked again', async () => {
		render(<ConversionStep2 />);

		const allCheckbox = screen.getByRole('checkbox', { name: /모두 동의/ });
		await userEvent.click(allCheckbox);
		await userEvent.click(allCheckbox);

		const checkboxes = screen.getAllByRole('checkbox');
		checkboxes.forEach((cb) => {
			expect(cb).not.toBeChecked();
		});
	});

	it('should toggle individual agreement', async () => {
		render(<ConversionStep2 />);

		const agree1 = screen.getByRole('checkbox', { name: /서비스 이용 약관/ });
		expect(agree1).not.toBeChecked();

		await userEvent.click(agree1);
		expect(agree1).toBeChecked();

		await userEvent.click(agree1);
		expect(agree1).not.toBeChecked();
	});

	it('should toggle accordion with aria-expanded', async () => {
		render(<ConversionStep2 />);

		const toggleButtons = screen.getAllByRole('button', { name: /상세내용/ });
		const firstToggle = toggleButtons[0];

		expect(firstToggle).toHaveAttribute('aria-expanded', 'false');

		await userEvent.click(firstToggle);
		expect(firstToggle).toHaveAttribute('aria-expanded', 'true');

		await userEvent.click(firstToggle);
		expect(firstToggle).toHaveAttribute('aria-expanded', 'false');
	});

	it('should render step 2 as active', () => {
		render(<ConversionStep2 />);

		const activeStep = screen.getByRole('listitem', { current: 'step' });
		expect(activeStep).toHaveTextContent('약관동의');
	});

	it('should mark step 1 as done', () => {
		render(<ConversionStep2 />);

		const stepList = screen.getByRole('list', { name: '진행 단계' });
		const items = stepList.querySelectorAll('li');
		expect(items[0]).toHaveClass('done');
	});
});
