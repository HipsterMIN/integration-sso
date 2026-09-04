import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import ConversionLayout from './index';

jest.mock('lib/history', () => ({
	__esModule: true,
	default: { push: jest.fn(), listen: jest.fn() },
}));

describe('ConversionLayout', () => {
	it('should render page title heading', () => {
		render(
			<ConversionLayout currentStep={1}>
				<div>content</div>
			</ConversionLayout>,
		);

		expect(
			screen.getByRole('heading', { name: '중기원패스 회원 전환', level: 2 }),
		).toBeInTheDocument();
	});

	it('should render current step name as heading', () => {
		render(
			<ConversionLayout currentStep={2}>
				<div>content</div>
			</ConversionLayout>,
		);

		expect(screen.getByRole('heading', { name: '약관동의', level: 3 })).toBeInTheDocument();
	});

	it('should render step counter with accessible label', () => {
		render(
			<ConversionLayout currentStep={3}>
				<div>content</div>
			</ConversionLayout>,
		);

		expect(screen.getByLabelText('3단계 / 6단계')).toBeInTheDocument();
	});

	it('should render children content', () => {
		render(
			<ConversionLayout currentStep={1}>
				<p>test children</p>
			</ConversionLayout>,
		);

		expect(screen.getByText('test children')).toBeInTheDocument();
	});

	it('should render only next button when prevRoute is not provided', () => {
		render(
			<ConversionLayout currentStep={1} nextRoute="/next">
				<div>content</div>
			</ConversionLayout>,
		);

		expect(screen.getByText('다음')).toBeInTheDocument();
		expect(screen.queryByText('이전')).not.toBeInTheDocument();
	});

	it('should render both buttons when both routes provided', () => {
		render(
			<ConversionLayout currentStep={2} prevRoute="/prev" nextRoute="/next">
				<div>content</div>
			</ConversionLayout>,
		);

		expect(screen.getByText('이전')).toBeInTheDocument();
		expect(screen.getByText('다음')).toBeInTheDocument();
	});

	it('should navigate when buttons are clicked', async () => {
		const history = require('lib/history').default;

		render(
			<ConversionLayout currentStep={2} prevRoute="/prev" nextRoute="/next">
				<div>content</div>
			</ConversionLayout>,
		);

		await userEvent.click(screen.getByText('이전'));
		expect(history.push).toHaveBeenCalledWith('/prev');

		await userEvent.click(screen.getByText('다음'));
		expect(history.push).toHaveBeenCalledWith('/next');
	});

	it('should not render button group when no routes provided', () => {
		render(
			<ConversionLayout currentStep={1}>
				<div>content</div>
			</ConversionLayout>,
		);

		expect(screen.queryByRole('group', { name: '페이지 이동' })).not.toBeInTheDocument();
	});

	it('should prevent default form submission', () => {
		render(
			<ConversionLayout currentStep={1}>
				<div>content</div>
			</ConversionLayout>,
		);

		const form = screen.getByRole('form');
		const submitEvent = new Event('submit', { bubbles: true, cancelable: true });
		const prevented = !form.dispatchEvent(submitEvent);
		expect(prevented).toBe(true);
	});
});
