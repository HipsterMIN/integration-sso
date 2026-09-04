import { render, screen } from '@testing-library/react';

import ConversionBusinessStep5 from './Step5';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionBusinessStep5', () => {
	it('should render business info fields', () => {
		render(<ConversionBusinessStep5 />);

		expect(screen.getByLabelText(/회사명/)).toBeInTheDocument();
		expect(screen.getByLabelText(/사업자등록번호/)).toBeInTheDocument();
		expect(screen.getByLabelText(/대표자명/)).toBeInTheDocument();
	});

	it('should render with business member type', () => {
		render(<ConversionBusinessStep5 />);

		const badge = screen.getByLabelText('기업 회원');
		expect(badge).toHaveTextContent('기업');
	});
});
