import MypageLayout from 'components/MypageLayout';
import Spinner from 'components/Spinner';
import { lazy, Suspense } from 'react';
import { Redirect, Route, Switch, useRouteMatch } from 'react-router-dom';

const Information = lazy(() => import('./pages/Information'));
const InformationStep2 = lazy(() => import('./pages/InformationStep2'));
const InformationStep3 = lazy(() => import('./pages/InformationStep3'));
const Affiliation = lazy(() => import('./pages/Affiliation'));
const AffiliationAddStep1 = lazy(() => import('./pages/AffiliationAddStep1'));
const AffiliationAddStep2 = lazy(() => import('./pages/AffiliationAddStep2'));
const AffiliationWithdrawStep1 = lazy(
	() => import('./pages/AffiliationWithdrawStep1'),
);
const AffiliationWithdrawStep2 = lazy(
	() => import('./pages/AffiliationWithdrawStep2'),
);
const PasswordStep1 = lazy(() => import('./pages/PasswordStep1'));
const Withdraw = lazy(() => import('./pages/Withdraw'));
const WithdrawStep2 = lazy(() => import('./pages/WithdrawStep2'));
const WithdrawComplete = lazy(() => import('./pages/WithdrawComplete'));

function MypageBusiness(): JSX.Element {
	const { path } = useRouteMatch();

	return (
		<MypageLayout memberType="business">
			<Suspense fallback={<Spinner size="large" tip="Loading..." />}>
				<Switch>
					<Route exact path={`${path}/information`} component={Information} />
					<Route
						exact
						path={`${path}/information/step2`}
						component={InformationStep2}
					/>
					<Route
						exact
						path={`${path}/information/step3`}
						component={InformationStep3}
					/>
					<Route exact path={`${path}/affiliation`} component={Affiliation} />
					<Route
						exact
						path={`${path}/affiliation/add/step1`}
						component={AffiliationAddStep1}
					/>
					<Route
						exact
						path={`${path}/affiliation/add/step2`}
						component={AffiliationAddStep2}
					/>
					<Route
						exact
						path={`${path}/affiliation/withdraw/step1`}
						component={AffiliationWithdrawStep1}
					/>
					<Route
						exact
						path={`${path}/affiliation/withdraw/step2`}
						component={AffiliationWithdrawStep2}
					/>
					<Route
						exact
						path={`${path}/withdraw/complete`}
						component={WithdrawComplete}
					/>
					<Route
						exact
						path={`${path}/withdraw/step2`}
						component={WithdrawStep2}
					/>
					<Route exact path={`${path}/withdraw`} component={Withdraw} />
					<Route exact path={`${path}/password`} component={PasswordStep1} />
					<Route exact path={path}>
						<Redirect to={`${path}/information`} />
					</Route>
				</Switch>
			</Suspense>
		</MypageLayout>
	);
}

export default MypageBusiness;
