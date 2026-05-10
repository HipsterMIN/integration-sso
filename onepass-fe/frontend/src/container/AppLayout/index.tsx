import './AppLayout.styles.scss';

import * as Sentry from '@sentry/react';
import { Tooltip, Button } from 'antd';
import getLocalStorageKey from 'api/browser/localstorage/get';
import { UcubeMenu } from 'assets/UcubeIcons';
import cx from 'classnames';
import { IS_SIDEBAR_COLLAPSED, IS_SIDEBAR_HIDED } from 'constants/app';
import SideNav from 'container/SideNav';
import TopNav from 'container/TopNav';
import useLicense from 'hooks/useLicense';
import Header from 'components/Header';
import Footer from 'components/Footer';
import ErrorBoundaryFallback from 'pages/ErrorBoundaryFallback/ErrorBoundaryFallback';
import {
	ReactNode,
	useCallback,
	useLayoutEffect,
	useState,
} from 'react';
import { Helmet } from 'react-helmet-async';
import { useTranslation } from 'react-i18next';
import { useDispatch } from 'react-redux';
import { useLocation } from 'react-router-dom';
import { Dispatch } from 'redux';
import { sideBarCollapse, sideBarHide } from 'store/actions';
import AppActions from 'types/actions';

import { getRouteKey } from './utils';

/**
 * 기본 레이아웃 - fullpage
 */
function BaseLayout({ children }: { children: ReactNode }): JSX.Element {
	const { pathname } = useLocation();
	const { t } = useTranslation(['titles']);
	const routeKey = getRouteKey(pathname);
	const pageTitle = t(routeKey);

	return (
		<>
			<Helmet>
				<title>{pageTitle}</title>
			</Helmet>
			<Header />
			<Sentry.ErrorBoundary fallback={<ErrorBoundaryFallback />}>
				{children}
			</Sentry.ErrorBoundary>
			<Footer />
		</>
	);
}

/**
 * 네비게이션 레이아웃 - SideNav + TopNav 포함
 */
function NavLayout({ children }: { children: ReactNode }): JSX.Element {
	const { data: licenseData, isFetching } = useLicense();
	const { pathname } = useLocation();
	const { t } = useTranslation(['titles']);
	const dispatch = useDispatch<Dispatch<AppActions | any>>();

	const [collapsed, setCollapsed] = useState<boolean>(
		getLocalStorageKey(IS_SIDEBAR_COLLAPSED) === 'true',
	);

	const [hided, setHided] = useState<boolean>(
		getLocalStorageKey(IS_SIDEBAR_HIDED) === 'true',
	);

	const [hoverMenu, setHoverMenu] = useState<boolean>(false);

	const onHide = useCallback(() => {
		setHided((prev) => {
			if (!prev) setCollapsed(true);
			return !prev;
		});
	}, []);

	const onCollapse = useCallback(() => {
		setCollapsed((prev) => {
			if (prev) {
				setHided(false);
				setHoverMenu(false);
			}
			return !prev;
		});
	}, []);

	const onMenuOver = useCallback(() => setHoverMenu(true), []);
	const onMenuOut = useCallback(() => setHoverMenu(false), []);

	useLayoutEffect(() => {
		dispatch(sideBarCollapse(collapsed));
		dispatch(sideBarHide(hided));
	}, [collapsed, hided, dispatch]);

	const routeKey = getRouteKey(pathname);
	const pageTitle = t(routeKey);

	return (
		<div
			className={cx(
				'navLayout',
				hided ? 'hided' : !collapsed ? 'docked' : '',
			)}
		>
			<Helmet>
				<title>{pageTitle}</title>
			</Helmet>

			<Tooltip title={hided ? 'Show Menu' : 'Hide Menu'} placement="right">
				<Button
					className="sidebar-btn nav-item-label menuBtn"
					icon={<UcubeMenu size={24} viewBox="0 0 24 24" />}
					onMouseOver={onMenuOver}
				/>
			</Tooltip>
			<SideNav
				licenseData={licenseData}
				isFetching={isFetching}
				onCollapse={onCollapse}
				collapsed={collapsed}
				onHide={onHide}
				hided={hided}
				hoverMenu={hoverMenu}
			/>
			<main
				className={cx(
					'navContent',
					hided ? 'hided' : collapsed ? 'collapsed' : '',
				)}
				onMouseOver={onMenuOut}
			>
				<Sentry.ErrorBoundary fallback={<ErrorBoundaryFallback />}>
					<TopNav />
					<section className="contents">{children}</section>
				</Sentry.ErrorBoundary>
			</main>
		</div>
	);
}

/**
 * AppLayout - layout 옵션에 따라 레이아웃 선택
 */
function AppLayout({ children, layout }: AppLayoutProps): JSX.Element {
	if (layout === 'nav') {
		return <NavLayout>{children}</NavLayout>;
	}

	return <BaseLayout>{children}</BaseLayout>;
}

interface AppLayoutProps {
	children: ReactNode;
	layout?: 'nav' | 'full';
}

export { BaseLayout, NavLayout };
export default AppLayout;
