import getFeaturesFlags from 'api/features/getFeatureFlags';
import { FeatureKeys } from 'constants/features';
import { REACT_QUERY_KEY } from 'constants/reactQueryKeys';
import { useQuery, UseQueryResult } from 'react-query';
import { useSelector } from 'react-redux';
import { AppState } from 'store/reducers';
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

const useGetFeatureFlag = (
	onSuccessHandler: (routes: FeatureFlagProps[]) => void,
): UseQueryResult<FeatureFlagProps[], unknown> => {
	const userId: string = useSelector<AppState, string>(
		(state) => state.app.user?.userId || '',
	);

	// API 엔드포인트가 설정되지 않은 경우 기본값 사용
	const hasApiEndpoint = !!process.env.FRONTEND_API_ENDPOINT;

	return useQuery<FeatureFlagProps[]>({
		queryFn: hasApiEndpoint ? getFeaturesFlags : () => Promise.resolve(defaultFeatureFlags),
		queryKey: [REACT_QUERY_KEY.GET_FEATURES_FLAGS, userId, hasApiEndpoint],
		onSuccess: onSuccessHandler,
		onError: (error) => {
			console.warn('Feature flags 로딩 실패, 기본값 사용:', error);
		},
		retry: false,
		retryOnMount: false,
		refetchOnMount: false,
		refetchOnWindowFocus: false,
		refetchOnReconnect: false,
		staleTime: Infinity,
		enabled: true, // 항상 활성화하되 queryFn에서 조건부 처리
	});
};

export default useGetFeatureFlag;
