/* eslint-disable @typescript-eslint/explicit-module-boundary-types */
import { createLucideIcon } from 'lucide-react';
import { ForwardRefExoticComponent, RefAttributes } from 'react';

interface UcubeIconProps {
	size?: number;
	viewBox?: string;
	onClick?: () => void;
}

export const createUcubeIcon = (
	iconName: string,
	iconNode: any[],
): ForwardRefExoticComponent<RefAttributes<any> | UcubeIconProps> =>
	createLucideIcon(
		iconName,
		iconNode.map((e, i) => [e[0], { ...e[1], key: i }]),
	);
