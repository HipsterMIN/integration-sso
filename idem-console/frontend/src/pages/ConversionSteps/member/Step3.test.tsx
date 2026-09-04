import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import ConversionStep3 from './Step3';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionStep3 (member)', () => {
	it('should render auth type radio group', () => {
		render(<ConversionStep3 />);

		expect(
			screen.getByRole('radiogroup', { name: '본인인증 방식 선택' }),
		).toBeInTheDocument();
	});

	it('should render both auth options', () => {
		render(<ConversionStep3 />);

		expect(screen.getByText('공동인증서')).toBeInTheDocument();
		expect(screen.getByText('개인 간편인증서')).toBeInTheDocument();
	});

	it('should select auth type when clicked', async () => {
		render(<ConversionStep3 />);

		const certRadio = screen.getByRole('radio', { name: /공동인증서/ });
		const appRadio = screen.getByRole('radio', { name: /개인 간편인증서/ });

		await userEvent.click(certRadio);
		expect(certRadio).toBeChecked();
		expect(appRadio).not.toBeChecked();
	});

	it('should render modals (closed by default)', () => {
		render(<ConversionStep3 />);

		const dialogs = screen.getAllByRole('dialog');
		dialogs.forEach((dialog) => {
			expect(dialog).not.toHaveClass('open');
		});
	});

	it('should render step 3 as active', () => {
		render(<ConversionStep3 />);

		const activeStep = screen.getByRole('listitem', { current: 'step' });
		expect(activeStep).toHaveTextContent('본인인증');
	});

	it('should mark steps 1-2 as done', () => {
		render(<ConversionStep3 />);

		const stepList = screen.getByRole('list', { name: '진행 단계' });
		const items = stepList.querySelectorAll('li');
		expect(items[0]).toHaveClass('done');
		expect(items[1]).toHaveClass('done');
	});
});

describe('ConversionStep3 (business)', () => {
	it('should render business auth radio group', () => {
		render(<ConversionStep3 memberType="business" />);

		expect(
			screen.getByRole('radiogroup', { name: '기업인증 방식 선택' }),
		).toBeInTheDocument();
	});

	it('should render business auth options', () => {
		render(<ConversionStep3 memberType="business" />);

		expect(screen.getByText('기업인증서')).toBeInTheDocument();
		expect(screen.getByText('사업자 간편인증서')).toBeInTheDocument();
	});

	it('should render business input fields', () => {
		render(<ConversionStep3 memberType="business" />);

		expect(screen.getByLabelText('사업자등록번호')).toBeInTheDocument();
	});

	it('should render step 3 as 기업인증', () => {
		render(<ConversionStep3 memberType="business" />);

		const activeStep = screen.getByRole('listitem', { current: 'step' });
		expect(activeStep).toHaveTextContent('기업인증');
	});
});
