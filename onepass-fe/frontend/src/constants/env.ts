export const ENVIRONMENT = {
	baseURL:
		process?.env?.FRONTEND_API_ENDPOINT ||
		process?.env?.GITPOD_WORKSPACE_URL?.replace('://', '://8080-') ||
		'',
	wsURL: process?.env?.WEBSOCKET_API_ENDPOINT || '',
};

export const QSIGN = {
	baseURL: process?.env?.QSIGN_BASE_URL || '',
	realm: process?.env?.QSIGN_REALM || '',
	clientId: process?.env?.QSIGN_CLIENT_ID || '',
};
