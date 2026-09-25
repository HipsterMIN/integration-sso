import { render, screen } from '@testing-library/react';

import ConversionBusinessStep4 from './Step4';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionBusinessStep4', () => {
	it('should render with business member type', () => {
		render(<ConversionBusinessStep4 />);

		const badge = screen.getByLabelText('기업 회원');
		expect(badge).toHaveTextContent('기업');
	});

	it('should render service list with select all', () => {
		render(<ConversionBusinessStep4 />);

		expect(screen.getByLabelText('유관시스템 서비스 선택')).toBeInTheDocument();
		expect(
			screen.getByText('통합회원 유관시스템 서비스 목록'),
		).toBeInTheDocument();
	});
});
