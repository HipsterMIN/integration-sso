export const getActiveMenuKeyFromPath = (pathname: string): string => {
	const basePath = pathname?.split('/')?.[1];

	if (!basePath) return '';

	return `/${basePath}`;
};
