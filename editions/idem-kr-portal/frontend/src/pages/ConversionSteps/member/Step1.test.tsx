import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import ConversionStep1 from './Step1';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionStep1', () => {
	it('should render member type radio group', () => {
		render(<ConversionStep1 />);

		expect(screen.getByRole('radiogroup', { name: '회원유형 선택' })).toBeInTheDocument();
	});

	it('should render both member type options', () => {
		render(<ConversionStep1 />);

		expect(screen.getByText('개인회원')).toBeInTheDocument();
		expect(screen.getByText('기업회원')).toBeInTheDocument();
	});

	it('should have member selected by default', () => {
		render(<ConversionStep1 />);

		const memberRadio = screen.getByRole('radio', { name: /개인회원/ });
		const businessRadio = screen.getByRole('radio', { name: /기업회원/ });

		expect(memberRadio).toBeChecked();
		expect(businessRadio).not.toBeChecked();
	});

	it('should switch to business when clicked', async () => {
		render(<ConversionStep1 />);

		const memberRadio = screen.getByRole('radio', { name: /개인회원/ });
		const businessRadio = screen.getByRole('radio', { name: /기업회원/ });

		await userEvent.click(businessRadio);
		expect(businessRadio).toBeChecked();
		expect(memberRadio).not.toBeChecked();
	});

	it('should render 14세 미만 button', () => {
		render(<ConversionStep1 />);

		expect(screen.getByText('14세 미만만 회원가입')).toBeInTheDocument();
	});

	it('should render step 1 as active', () => {
		render(<ConversionStep1 />);

		expect(screen.getByLabelText(/1단계 \/ 6단계/)).toBeInTheDocument();
		expect(screen.getByRole('heading', { name: '회원유형', level: 3 })).toBeInTheDocument();
	});

	it('should switch to business layout when 기업회원 selected', async () => {
		render(<ConversionStep1 />);

		await userEvent.click(screen.getByRole('radio', { name: /기업회원/ }));
		expect(screen.getByRole('main')).toHaveClass('business');
	});

	it('should render next button since member is default selected', () => {
		render(<ConversionStep1 />);

		expect(screen.getByText('다음')).toBeInTheDocument();
	});
});
