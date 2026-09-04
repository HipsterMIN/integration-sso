import { render, screen } from '@testing-library/react';

import ConversionBusinessStep3 from './Step3';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionBusinessStep3', () => {
	it('should render business auth options', () => {
		render(<ConversionBusinessStep3 />);

		expect(screen.getByText('기업인증서')).toBeInTheDocument();
		expect(screen.getByText('사업자 간편인증서')).toBeInTheDocument();
	});

	it('should render business input fields', () => {
		render(<ConversionBusinessStep3 />);

		expect(screen.getByLabelText('사업자등록번호')).toBeInTheDocument();
	});

	it('should render step 3 as 기업인증', () => {
		render(<ConversionBusinessStep3 />);

		const activeStep = screen.getByRole('listitem', { current: 'step' });
		expect(activeStep).toHaveTextContent('기업인증');
	});
});
