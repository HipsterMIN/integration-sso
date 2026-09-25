type FiveMin = '5m';
type TenMin = '10m';
type FifteenMin = '15m';
type ThirtyMin = '30m';
type OneMin = '1m';
type SixHour = '6h';
type OneHour = '1h';
type FourHour = '4h';
type ThreeHour = '3h';
type TwelveHour = '12h';
type OneDay = '1d';
type ThreeDay = '3d';
type OneWeek = '1w';
type Custom = 'custom';

export type Time =
	| FiveMin
	| TenMin
	| FifteenMin
	| ThirtyMin
	| OneMin
	| FourHour
	| SixHour
	| OneHour
	| ThreeHour
	| Custom
	| OneWeek
	| OneDay
	| TwelveHour
	| ThreeDay;

export const Options: Option[] = [
	{ value: '5m', label: 'Last 5 min' },
	{ value: '15m', label: 'Last 15 min' },
	{ value: '30m', label: 'Last 30 min' },
	{ value: '1h', label: 'Last 1 hour' },
	{ value: '6h', label: 'Last 6 hour' },
	{ value: '1d', label: 'Last 1 day' },
	{ value: '3d', label: 'Last 3 days' },
	{ value: '1w', label: 'Last 1 week' },
	{ value: 'custom', label: 'Custom' },
];

type TimeFrame = {
	'5min': string;
	'15min': string;
	'30min': string;
	'1hr': string;
	'6hr': string;
	'1day': string;
	'3days': string;
	'1week': string;
	[key: string]: string;
};

export const RelativeTimeMap: TimeFrame = {
	'5min': '5m',
	'15min': '15m',
	'30min': '30m',
	'1hr': '1h',
	'6hr': '6h',
	'1day': '1d',
	'3days': '3d',
	'1week': '1w',
};

export interface Option {
	value: Time;
	label: string;
}

export const RelativeDurationOptions: Option[] = [
	{ value: '5m', label: 'Last 5 min' },
	{ value: '15m', label: 'Last 15 min' },
	{ value: '30m', label: 'Last 30 min' },
	{ value: '1h', label: 'Last 1 hour' },
	{ value: '6h', label: 'Last 6 hour' },
	{ value: '1d', label: 'Last 1 day' },
	{ value: '3d', label: 'Last 3 days' },
	{ value: '1w', label: 'Last 1 week' },
];

export const getDefaultOption = (): Time => Options[2].value;

export const getOptions = (): Option[] => Options;

export const routesToHideBreadCrumbs: string[] = [];

export const routesToSkip: string[] = [];

export const routesToDisable: string[] = [];

export interface LocalStorageTimeRange {
	localstorageStartTime: string | null;
	localstorageEndTime: string | null;
}

export interface TimeRange {
	startTime: string;
	endTime: string;
}
