import { render, screen } from '@testing-library/react';

import ConversionBusinessStep2 from './Step2';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionBusinessStep2', () => {
	it('should render with business member type', () => {
		render(<ConversionBusinessStep2 />);

		const badge = screen.getByLabelText('기업 회원');
		expect(badge).toHaveTextContent('기업');
	});

	it('should render agree items', () => {
		render(<ConversionBusinessStep2 />);

		expect(screen.getByText('모두 동의합니다.')).toBeInTheDocument();
	});
});
