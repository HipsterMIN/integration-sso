import axios from 'api';
import { FeatureKeys } from 'constants/features';
import { ApiResponse } from 'types/api';
import { FeatureFlagProps } from 'types/api/features/getFeaturesFlags';

// API가 없을 때 사용할 기본 feature flags
const defaultFeatureFlags: FeatureFlagProps[] = [
	{
		name: FeatureKeys.OSS,
		active: true,
		usage: 0,
		usage_limit: -1,
		route: '',
	},
	{
		name: FeatureKeys.ONBOARDING,
		active: false,
		usage: 0,
		usage_limit: -1,
		route: '',
	},
];

const getFeaturesFlags = (): Promise<FeatureFlagProps[]> =>
	axios
		.get<ApiResponse<FeatureFlagProps[]>>(`/featureFlags`)
		.then((response) => response.data.data)
		.catch((error) => {
			const isDevelopment = process.env.NODE_ENV === 'development';
			if (isDevelopment) {
				console.warn('Feature flags API 호출 실패, 개발환경 기본값을 사용합니다:', error);
				return defaultFeatureFlags;
			}
			throw error;
		});

export default getFeaturesFlags;
