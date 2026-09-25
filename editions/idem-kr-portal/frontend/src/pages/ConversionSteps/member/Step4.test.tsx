import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import ConversionStep4 from './Step4';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionStep4', () => {
	it('should render all 7 systems', () => {
		render(<ConversionStep4 />);

		expect(screen.getByText('스마트공장 사업관리시스템')).toBeInTheDocument();
		expect(screen.getByText('K스타트업')).toBeInTheDocument();
		expect(screen.getByText('창업기업 확인시스템')).toBeInTheDocument();
		expect(screen.getAllByRole('checkbox')).toHaveLength(7);
	});

	it('should toggle checkbox when clicked', async () => {
		render(<ConversionStep4 />);

		const checkboxes = screen.getAllByRole('checkbox');
		expect(checkboxes[0]).not.toBeChecked();

		await userEvent.click(checkboxes[0]);
		expect(checkboxes[0]).toBeChecked();

		await userEvent.click(checkboxes[0]);
		expect(checkboxes[0]).not.toBeChecked();
	});

	it('should render info list', () => {
		render(<ConversionStep4 />);

		expect(screen.getByLabelText('안내 사항')).toBeInTheDocument();
		expect(screen.getByText(/하나의 통합 ID로 연결/)).toBeInTheDocument();
	});

	it('should render step 4 as active', () => {
		render(<ConversionStep4 />);

		const activeStep = screen.getByRole('listitem', { current: 'step' });
		expect(activeStep).toHaveTextContent('서비스연결');
	});

	it('should mark steps 1-3 as done', () => {
		render(<ConversionStep4 />);

		const stepList = screen.getByRole('list', { name: '진행 단계' });
		const items = stepList.querySelectorAll('li');
		expect(items[0]).toHaveClass('done');
		expect(items[1]).toHaveClass('done');
		expect(items[2]).toHaveClass('done');
	});
});
