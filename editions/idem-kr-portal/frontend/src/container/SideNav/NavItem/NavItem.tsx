/* eslint-disable no-nested-ternary */
/* eslint-disable react/require-default-props */
/* eslint-disable jsx-a11y/no-static-element-interactions */
/* eslint-disable jsx-a11y/click-events-have-key-events */
import './NavItem.styles.scss';

import { Tag } from 'antd';
import cx from 'classnames';
import { isEmpty } from 'lodash-es';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { SidebarItem } from '../sideNav.types';
import { useLocation } from 'react-router-dom';

export default function NavItem({
	depth = 0,
	// top = 0,
	// index = -1,
	item,
	isActive,
	onClick,
	handleMenuItemClick,
}: {
	depth?: number;
	// top?: number;
	// index?: number;
	item: SidebarItem;
	isActive: boolean;
	onClick?: (event: React.MouseEvent<HTMLDivElement, MouseEvent>) => void;
	handleMenuItemClick?: (event: React.MouseEvent, item: SidebarItem) => void;
}): JSX.Element {
	const { t } = useTranslation(['menus']);
	const { label, icon, children = [], isBeta, isNew } = item;
	const ref = useRef<HTMLDivElement>(null);
	const location = useLocation(); // 현재 URL 가져오기
  	const [active, setActive] = useState(false);

	// const menuTop = top > 0 ? top : index >= 0 ? 158 + 48 * index : 0;
	// TODO 비활성화 메뉴 추후 제거필요
	// const disabledMenu = ['GCP', 'Naver', 'KT', 'NHN', 'SCP'];
	const disabledMenu = [''];

	// 하위 메뉴 없는지 체크
	const subMenu = isEmpty(children) || isEmpty(children.filter((e) => e.hidden !== true));

	const isDescendantActive = (pathname: string, item: SidebarItem): boolean => {
		if (pathname === item.key) return true; // 현재 아이템과 정확히 일치하는 경우
		if (item.children) {
		  return item.children.some((child) => isDescendantActive(pathname, child)); // 재귀적으로 하위 메뉴 검사
		}
		return false;
	};

	useEffect(() => {
		const currentPath = location?.pathname ?? "";
		setActive(isDescendantActive(currentPath, item)); // 현재 아이템 또는 하위 아이템이 활성화되면 active 설정
	}, [location?.pathname, item]);

	return (
		<div
			className={cx("nav-item", isActive ? "active" : "", subMenu ? "" : "has-sub")}
			onClick={(event): void => {
				event.stopPropagation();
				const currentItem = event.currentTarget; // 현재 클릭한 요소
				const parentNav = ref?.current?.parentElement;
				if (parentNav) {
					const navItems = parentNav.querySelectorAll(".nav-item");

					// 현재 요소에 "on" 클래스가 있는지 확인
					const isCurrentlyActive = currentItem.classList.contains("on");

					// 모든 nav-item에서 "on" 클래스 제거
					navItems.forEach((navItem) => navItem.classList.remove("on"));

					// 현재 요소가 비활성화된 상태였다면 "on" 추가 (토글 기능)
					if (!isCurrentlyActive) {
						currentItem.classList.add("on");
					}
				}

				// TODO 비활성화 메뉴 추후 제거 필요
				const disabled = disabledMenu.find((menu) => menu === item.key);

				// 하위 메뉴가 없는 경우에만 handleMenuItemClick 호출
				if (subMenu && handleMenuItemClick && !disabled) {
					handleMenuItemClick(event, item);
				}
				if (onClick) onClick(event);
			}}
			ref={ref}
		>
			{/* <div className="nav-item-active-marker" /> */}
			<div className={cx('nav-item-data', isBeta ? 'beta-tag' : '')}>
				{/* TODO 데모 완료 후 임시 비활성화 제거필요 */}
				{!isEmpty(icon) && <div className="nav-item-icon">{icon}</div>}
				{/* {label === 'GCP' ||
				label === 'Naver' ||
				label === 'KT' ||
				label === 'NHN' ||
				label === 'SCP' ? (
					<div className={cx(`nav-item-label disabled`)}>{t(`${label}`)}</div>
				) : (
					<div className="nav-item-label">{t(`${label}`)}</div>
				)} */}
				<div className="nav-item-label">{t(`${label}`)}</div>
				{isBeta && (
					<div className="nav-item-beta">
						<Tag bordered={false} color="geekblue">
							Beta
						</Tag>
					</div>
				)}

				{isNew && (
					<div className="nav-item-new">
						<Tag bordered={false} className="sidenav-new-tag">
							New
						</Tag>
					</div>
				)}
			</div>

			{!isEmpty(children) && !isEmpty(children.filter((e) => e.hidden !== true)) && (
				<div
					className={cx('nav-item-sub', `nav-items-depth${depth + 1}`)}
					// style={{ menutop }}
				>
					{children.map((childItem, index) => (
						<NavItem
						key={childItem.key || index}
						depth={depth + 1}
						item={childItem}
						isActive={isDescendantActive(location?.pathname ?? "", childItem)} // 하위 메뉴도 URL 매칭 검사
						handleMenuItemClick={handleMenuItemClick}
						/>
					))}
				</div>
			)}
		</div>
	);
}
