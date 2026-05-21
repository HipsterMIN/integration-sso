import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import ConversionStep5 from './Step5';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionStep5 (member)', () => {
	it('should render account fields', () => {
		render(<ConversionStep5 />);

		expect(document.getElementById('id')).toBeInTheDocument();
		expect(document.getElementById('password')).toBeInTheDocument();
		expect(document.getElementById('password_check')).toBeInTheDocument();
	});

	it('should render member info fields', () => {
		render(<ConversionStep5 />);

		expect(document.getElementById('name')).toBeInTheDocument();
		expect(document.getElementById('phone2')).toBeInTheDocument();
		expect(document.getElementById('email1')).toBeInTheDocument();
	});

	it('should render notification checkboxes', () => {
		render(<ConversionStep5 />);

		expect(screen.getByLabelText('문자')).toBeInTheDocument();
		expect(screen.getByLabelText('알림톡(카카오톡)')).toBeInTheDocument();
		expect(screen.getByLabelText('이메일수신')).toBeInTheDocument();
	});

	it('should toggle notification checkbox', async () => {
		render(<ConversionStep5 />);

		const smsCheckbox = screen.getByLabelText('문자');
		expect(smsCheckbox).not.toBeChecked();

		await userEvent.click(smsCheckbox);
		expect(smsCheckbox).toBeChecked();

		await userEvent.click(smsCheckbox);
		expect(smsCheckbox).not.toBeChecked();
	});

	it('should render step 5 as active', () => {
		render(<ConversionStep5 />);

		const activeStep = screen.getByRole('listitem', { current: 'step' });
		expect(activeStep).toHaveTextContent('정보입력');
	});

	it('should mark steps 1-4 as done', () => {
		render(<ConversionStep5 />);

		const stepList = screen.getByRole('list', { name: '진행 단계' });
		const items = stepList.querySelectorAll('li');
		expect(items[0]).toHaveClass('done');
		expect(items[1]).toHaveClass('done');
		expect(items[2]).toHaveClass('done');
		expect(items[3]).toHaveClass('done');
	});
});

describe('ConversionStep5 (business)', () => {
	it('should render business info fields', () => {
		render(<ConversionStep5 memberType="business" />);

		expect(document.getElementById('id')).toBeInTheDocument();
		expect(document.getElementById('rep_name')).toBeInTheDocument();
		expect(document.getElementById('business_num')).toBeInTheDocument();
	});
});
