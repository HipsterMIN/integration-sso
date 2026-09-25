import { render, screen } from '@testing-library/react';

import StepIndicator, { STEPS } from './index';

describe('StepIndicator', () => {
	it('should render all 8 steps', () => {
		render(<StepIndicator currentStep={1} />);

		STEPS.forEach((name) => {
			expect(screen.getByText(name)).toBeInTheDocument();
		});
	});

	it('should mark current step as active with aria-current', () => {
		render(<StepIndicator currentStep={3} />);

		const items = screen.getAllByRole('listitem');
		expect(items[2]).toHaveAttribute('aria-current', 'step');
		expect(items[0]).not.toHaveAttribute('aria-current');
		expect(items[4]).not.toHaveAttribute('aria-current');
	});

	it('should mark previous steps as done', () => {
		render(<StepIndicator currentStep={3} />);

		const items = screen.getAllByRole('listitem');
		expect(items[0]).toHaveClass('done');
		expect(items[1]).toHaveClass('done');
		expect(items[2]).toHaveClass('active');
		expect(items[3]).not.toHaveClass('done');
	});

	it('should show sr-only text for done and active steps', () => {
		render(<StepIndicator currentStep={2} />);

		expect(screen.getByText('완료')).toBeInTheDocument();
		expect(screen.getByText('현재단계')).toBeInTheDocument();
	});

	it('should have accessible label on the list', () => {
		render(<StepIndicator currentStep={1} />);

		expect(screen.getByRole('list')).toHaveAttribute('aria-label', '진행 단계');
	});
});
