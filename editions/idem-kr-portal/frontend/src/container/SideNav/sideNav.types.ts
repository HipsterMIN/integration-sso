import { MenuProps } from 'antd';
import { ReactNode } from 'react';

export type MenuItem = Required<MenuProps>['items'][number];

export type SidebarMenu = MenuItem & {
	tags?: string[];
};

export interface SidebarItem {
	icon?: ReactNode;
	text?: ReactNode;
	key: string; // | number;
	label?: ReactNode;
	children?: SidebarItem[];
	isBeta?: boolean;
	isPopup?: boolean;
	hidden?: boolean;
	isNew?: boolean;
	isDev?: boolean;
}

export enum SecondaryMenuItemKey {
	Slack = 'slack',
	Version = 'version',
	Support = 'support',
}
