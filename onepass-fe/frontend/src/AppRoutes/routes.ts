import ROUTES from 'constants/routes';
import { RouteProps } from 'react-router-dom';

import {
	ConversionBusinessStep2,
	ConversionBusinessStep3,
	ConversionBusinessStep4,
	ConversionBusinessStep5,
	ConversionMemberStep2,
	ConversionMemberStep3,
	ConversionMemberStep4,
	ConversionMemberStep5,
	ConversionStep1,
	Login,
	MypageBusiness,
	MypageMember,
	OacxTest,
	Privacy,
	RegisterBusinessStep2,
	RegisterBusinessStep3,
	RegisterBusinessStep4,
	RegisterBusinessStep5,
	RegisterMemberStep2,
	RegisterMemberStep3,
	RegisterMemberStep4,
	RegisterMemberStep5,
	RegisterStep1,
	SomethingWentWrong,
	UnAuthorized,
	UseTerms,
} from './pageComponents';

const routes: AppRoutes[] = [
	{
		path: ROUTES.LOGIN,
		exact: true,
		component: Login,
		isPrivate: false,
		key: 'LOGIN',
	},
	{
		path: ROUTES.UN_AUTHORIZED,
		exact: true,
		component: UnAuthorized,
		key: 'UN_AUTHORIZED',
		isPrivate: true,
	},
	{
		path: ROUTES.SOMETHING_WENT_WRONG,
		exact: true,
		component: SomethingWentWrong,
		key: 'SOMETHING_WENT_WRONG',
		isPrivate: false,
	},
	{
		path: ROUTES.CONVERSION_STEP1,
		exact: true,
		component: ConversionStep1,
		isPrivate: false,
		key: 'CONVERSION_STEP1',
	},
	{
		path: ROUTES.CONVERSION_MEMBER_STEP2,
		exact: true,
		component: ConversionMemberStep2,
		isPrivate: false,
		key: 'CONVERSION_MEMBER_STEP2',
	},
	{
		path: ROUTES.CONVERSION_MEMBER_STEP3,
		exact: true,
		component: ConversionMemberStep3,
		isPrivate: false,
		key: 'CONVERSION_MEMBER_STEP3',
	},
	{
		path: ROUTES.CONVERSION_MEMBER_STEP4,
		exact: true,
		component: ConversionMemberStep4,
		isPrivate: false,
		key: 'CONVERSION_MEMBER_STEP4',
	},
	{
		path: ROUTES.CONVERSION_MEMBER_STEP5,
		exact: true,
		component: ConversionMemberStep5,
		isPrivate: false,
		key: 'CONVERSION_MEMBER_STEP5',
	},
	{
		path: ROUTES.CONVERSION_BUSINESS_STEP2,
		exact: true,
		component: ConversionBusinessStep2,
		isPrivate: false,
		key: 'CONVERSION_BUSINESS_STEP2',
	},
	{
		path: ROUTES.CONVERSION_BUSINESS_STEP3,
		exact: true,
		component: ConversionBusinessStep3,
		isPrivate: false,
		key: 'CONVERSION_BUSINESS_STEP3',
	},
	{
		path: ROUTES.CONVERSION_BUSINESS_STEP4,
		exact: true,
		component: ConversionBusinessStep4,
		isPrivate: false,
		key: 'CONVERSION_BUSINESS_STEP4',
	},
	{
		path: ROUTES.CONVERSION_BUSINESS_STEP5,
		exact: true,
		component: ConversionBusinessStep5,
		isPrivate: false,
		key: 'CONVERSION_BUSINESS_STEP5',
	},
	{
		path: ROUTES.REGISTER_STEP1,
		exact: true,
		component: RegisterStep1,
		isPrivate: false,
		key: 'REGISTER_STEP1',
	},
	{
		path: ROUTES.REGISTER_MEMBER_STEP2,
		exact: true,
		component: RegisterMemberStep2,
		isPrivate: false,
		key: 'REGISTER_MEMBER_STEP2',
	},
	{
		path: ROUTES.REGISTER_MEMBER_STEP3,
		exact: true,
		component: RegisterMemberStep3,
		isPrivate: false,
		key: 'REGISTER_MEMBER_STEP3',
	},
	{
		path: ROUTES.REGISTER_MEMBER_STEP4,
		exact: true,
		component: RegisterMemberStep4,
		isPrivate: false,
		key: 'REGISTER_MEMBER_STEP4',
	},
	{
		path: ROUTES.REGISTER_MEMBER_STEP5,
		exact: true,
		component: RegisterMemberStep5,
		isPrivate: false,
		key: 'REGISTER_MEMBER_STEP5',
	},
	{
		path: ROUTES.REGISTER_BUSINESS_STEP2,
		exact: true,
		component: RegisterBusinessStep2,
		isPrivate: false,
		key: 'REGISTER_BUSINESS_STEP2',
	},
	{
		path: ROUTES.REGISTER_BUSINESS_STEP3,
		exact: true,
		component: RegisterBusinessStep3,
		isPrivate: false,
		key: 'REGISTER_BUSINESS_STEP3',
	},
	{
		path: ROUTES.REGISTER_BUSINESS_STEP4,
		exact: true,
		component: RegisterBusinessStep4,
		isPrivate: false,
		key: 'REGISTER_BUSINESS_STEP4',
	},
	{
		path: ROUTES.REGISTER_BUSINESS_STEP5,
		exact: true,
		component: RegisterBusinessStep5,
		isPrivate: false,
		key: 'REGISTER_BUSINESS_STEP5',
	},
	{
		path: ROUTES.MYPAGE_MEMBER,
		exact: false,
		component: MypageMember,
		isPrivate: true,
		key: 'MYPAGE_MEMBER',
	},
	{
		path: ROUTES.MYPAGE_BUSINESS,
		exact: false,
		component: MypageBusiness,
		isPrivate: true,
		key: 'MYPAGE_BUSINESS',
	},
	{
		path: ROUTES.USE_TERMS,
		exact: true,
		component: UseTerms,
		isPrivate: false,
		key: 'USE_TERMS',
	},
	{
		path: ROUTES.PRIVACY,
		exact: true,
		component: Privacy,
		isPrivate: false,
		key: 'PRIVACY',
	},
	{
		path: ROUTES.OACX_TEST,
		exact: true,
		component: OacxTest,
		isPrivate: false,
		key: 'OACX_TEST',
	},
];

export interface AppRoutes {
	component: RouteProps['component'];
	path: RouteProps['path'];
	exact: RouteProps['exact'];
	isPrivate: boolean;
	key: keyof typeof ROUTES;
	layout?: 'nav' | 'full';
}

export default routes;
