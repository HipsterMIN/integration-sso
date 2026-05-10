import './CustomTimePicker.styles.scss';

import { Button } from 'antd';
import { RangePickerProps } from 'antd/lib/date-picker';
import cx from 'classnames';
// import ROUTES from 'constants/routes';
import { DateTimeRangeType } from 'container/TopNav/CustomDateTimeModal';
import {
	LexicalContext,
	Option,
	RelativeDurationSuggestionOptionsV2,
} from 'container/TopNav/DateTimeSelectionV2/config';
import { Dispatch, SetStateAction, useState } from 'react';

// import { useLocation } from 'react-router-dom';
import RangePickerModal from './RangePickerModal';

interface CustomTimePickerPopoverContentProps {
	// options: any[];
	setIsOpen: Dispatch<SetStateAction<boolean>>;
	// customDateTimeVisible: boolean;
	setCustomDTPickerVisible: Dispatch<SetStateAction<boolean>>;
	onCustomDateHandler: (
		dateTimeRange: DateTimeRangeType,
		lexicalContext?: LexicalContext,
	) => void;
	onSelectHandler: (label: string, value: string) => void;
	// handleGoLive: () => void;
	selectedTime: string;
	selectedTimeLabel: string;
	customTime: RangePickerProps['value'];
	setCustomTime: Dispatch<SetStateAction<RangePickerProps['value']>>;
}

function CustomTimePickerPopoverContent({
	// options,
	setIsOpen,
	// customDateTimeVisible,
	setCustomDTPickerVisible,
	onCustomDateHandler,
	onSelectHandler,
	// handleGoLive,
	selectedTime,
	selectedTimeLabel,
	customTime,
	setCustomTime,
}: CustomTimePickerPopoverContentProps): JSX.Element {
	const [isRangeDate, setIsRangeDate] = useState<boolean>(false);

	function getTimeChips(options: Option[]): JSX.Element {
		return (
			<div className="relative-date-time-section">
				{options.map((option) => (
					<Button
						className={cx(
							'time-btns',
							selectedTime === option.value && selectedTimeLabel === option.label
								? 'is-active'
								: '',
						)}
						key={option.label + option.value}
						onClick={(): void => {
							onSelectHandler(option.label, option.value);
						}}
					>
						{option.label}
					</Button>
				))}
			</div>
		);
	}

	const onOpenChange = (open: boolean): void => {
		setIsRangeDate(open);
	};

	return (
		<div className="date-time-popover">
			<div
				className={cx(
					'relative-date-time',
					isRangeDate ? 'date-picker' : 'relative-times',
				)}
			>
				<div className="relative-times-container">
					<p className="time-heading">Quick Ranges</p>
					<div>{getTimeChips(RelativeDurationSuggestionOptionsV2)}</div>
				</div>
				{/* TODO 개발요청
					RangePickerModal 에서 input에 focus가 되면서 달력이 노출되면 
					.realtive-date-time 에 'date-picker' class가 추가되게 해주세요.
				*/}
				<div className="date-time-picker-container">
					<p className="time-heading">Time Ranges</p>
					<RangePickerModal
						setCustomDTPickerVisible={setCustomDTPickerVisible}
						setIsOpen={setIsOpen}
						onCustomDateHandler={onCustomDateHandler}
						selectedTime={selectedTime}
						customTime={customTime}
						setCustomTime={setCustomTime}
						onOpenChange={onOpenChange}
					/>
				</div>
			</div>
		</div>
	);
}

export default CustomTimePickerPopoverContent;
