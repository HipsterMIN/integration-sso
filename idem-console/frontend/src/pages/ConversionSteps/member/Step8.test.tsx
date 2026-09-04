import { render, screen } from '@testing-library/react';

import ConversionStep8 from './Step8';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionStep8 (member)', () => {
	it('should render completion title for member', () => {
		render(<ConversionStep8 />);

		expect(
			screen.getByText(/중기원패스 회원\(개인\) 전환을 완료하였습니다/),
		).toBeInTheDocument();
	});

	it('should render completion message', () => {
		render(<ConversionStep8 />);

		expect(screen.getByText(/편리하게 이용해 보세요/)).toBeInTheDocument();
	});

	it('should render step 6 as active', () => {
		render(<ConversionStep8 />);

		expect(screen.getByLabelText(/6단계 \/ 6단계/)).toBeInTheDocument();
		expect(screen.getByRole('heading', { name: '전환완료', level: 3 })).toBeInTheDocument();
	});

	it('should render total steps as 6 for member', () => {
		render(<ConversionStep8 />);

		expect(screen.getByLabelText(/6단계 \/ 6단계/)).toBeInTheDocument();
	});
});

describe('ConversionStep8 (business)', () => {
	it('should render completion title for business', () => {
		render(<ConversionStep8 memberType="business" />);

		expect(
			screen.getByText(/중기원패스 회원\(기업\) 전환을 완료하였습니다/),
		).toBeInTheDocument();
	});

	it('should render step 6 as 전환완료', () => {
		render(<ConversionStep8 memberType="business" currentStep={6} />);

		expect(screen.getByLabelText(/6단계 \/ 6단계/)).toBeInTheDocument();
		expect(screen.getByRole('heading', { name: '전환완료', level: 3 })).toBeInTheDocument();
		expect(screen.getByRole('main')).toHaveClass('business');
	});
});
