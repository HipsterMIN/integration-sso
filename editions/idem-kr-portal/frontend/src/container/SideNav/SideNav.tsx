import './SideNav.styles.scss';

import { Button, Tooltip } from 'antd';
import { UcubePanelRight } from 'assets/UcubeIcons';
import UcubeLogoLight from 'assets/UcubeLogoLight';
import cx from 'classnames';
import ROUTES from 'constants/routes';
import history from 'lib/history';
import { MouseEvent, useCallback, useMemo } from 'react';
import { useLocation } from 'react-router-dom';

import { routeConfig } from './config';
import { getQueryString } from './helper';
import defaultMenuItems from './menuItems';
import NavItem from './NavItem/NavItem';
import { SidebarItem } from './sideNav.types';
import { getActiveMenuKeyFromPath } from './sideNav.utils';

function SideNav({
	onCollapse,
	collapsed,
	hided,
	hoverMenu = false,
}: {
	licenseData?: any;
	isFetching?: boolean;
	onCollapse: () => void;
	collapsed: boolean;
	onHide?: () => void;
	hided: boolean;
	hoverMenu: boolean;
}): JSX.Element {
	const { pathname, search } = useLocation();

	const isCtrlMetaKey = (e: MouseEvent): boolean => e.ctrlKey || e.metaKey;

	const openInNewTab = (path: string): void => {
		window.open(path, '_blank');
	};

	const onClickHandler = useCallback(
		(key: string, event: MouseEvent | null) => {
			const params = new URLSearchParams(search);
			const availableParams = routeConfig[key];

			const queryString = getQueryString(availableParams || [], params);

			if (pathname !== key) {
				if (event && isCtrlMetaKey(event)) {
					openInNewTab(`${key}?${queryString.join('&')}`);
				} else {
					history.push(`${key}?${queryString.join('&')}`);
				}
			}
		},
		[pathname, search],
	);

	const activeMenuKey = useMemo(() => getActiveMenuKeyFromPath(pathname), [
		pathname,
	]);

	const handleMenuItemClick = (event: MouseEvent, item: SidebarItem): void => {
		onClickHandler(item?.key as string, event);
	};

	return (
		<div
			className={cx(
				'sidenav-container',
				hided ? 'hided' : !collapsed ? 'docked' : '',
				hoverMenu ? 'hover-menu' : '',
			)}
		>
			<div
				className={cx(
					'sideNav',
					hided ? 'hided' : !collapsed ? 'docked' : '',
					hoverMenu ? 'hover-menu' : '',
				)}
			>
				<Tooltip
					title={collapsed ? 'Dock Sidebar' : 'Undock Sidebar'}
					placement="right"
				>
					<Button
						className="sidebar-btn nav-item-label dockBtn"
						icon={<UcubePanelRight size={16} viewBox="0 0 16 16" />}
						onClick={onCollapse}
					/>
				</Tooltip>
				<div className="brand">
					<div className="brand-company-meta">
						<div
							className="brand-logo"
							onClick={(event: MouseEvent): void => {
								onClickHandler(ROUTES.HOME_PAGE, event);
							}}
						>
							<UcubeLogoLight />
						</div>
					</div>
				</div>

				<div className="nav-wrapper">
					<div className="primary-nav-items">
						{defaultMenuItems
							.filter((e) => e.hidden !== true)
							.map((item: SidebarItem, index: any) => (
								<NavItem
									depth={1}
									key={item.key || index}
									item={item}
									isActive={activeMenuKey === item.key}
									handleMenuItemClick={handleMenuItemClick}
								/>
							))}
					</div>
				</div>
			</div>
		</div>
	);
}

export default SideNav;
