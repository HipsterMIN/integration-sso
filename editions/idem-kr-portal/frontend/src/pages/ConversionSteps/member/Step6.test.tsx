import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import ConversionStep6 from './Step6';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionStep6', () => {
	it('should render select all checkbox', () => {
		render(<ConversionStep6 />);

		expect(document.getElementById('agree_all')).toBeInTheDocument();
	});

	it('should render service list title with count badge', () => {
		render(<ConversionStep6 />);

		expect(screen.getByText('통합회원 유관시스템 서비스 목록')).toBeInTheDocument();
		expect(screen.getByText(`+${14}`)).toBeInTheDocument();
	});

	it('should toggle select all checkbox', async () => {
		render(<ConversionStep6 />);

		const selectAll = document.getElementById('agree_all') as HTMLInputElement;
		expect(selectAll).not.toBeChecked();

		await userEvent.click(selectAll);
		expect(selectAll).toBeChecked();
	});

	it('should open service modal when arrow button clicked', async () => {
		render(<ConversionStep6 />);

		const modal = document.getElementById('modal_service_list')!;
		expect(modal).not.toHaveClass('open');

		const arrowBtn = screen.getByRole('button', { name: '통합회원 유관시스템 서비스 목록 보기' });
		await userEvent.click(arrowBtn);

		expect(modal).toHaveClass('open');
	});

	it('should render step 6 as active', () => {
		render(<ConversionStep6 />);

		const activeStep = screen.getByRole('listitem', { current: 'step' });
		expect(activeStep).toHaveTextContent('서비스연결');
	});

	it('should mark steps 1-3 as done', () => {
		render(<ConversionStep6 />);

		const stepList = screen.getByRole('list', { name: '진행 단계' });
		const items = stepList.querySelectorAll('li');
		for (let i = 0; i < 3; i++) {
			expect(items[i]).toHaveClass('done');
		}
	});
});
