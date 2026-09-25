import { QueryParams } from 'constants/query';
import ROUTES from 'constants/routes';

export const styles = { background: '#1f1f1f' };

export const subMenuStyles = {
	background: '#1f1f1f',
	margin: '0rem',
	width: '100%',
	color: '#DBDBDB',
};

export const routeConfig: Record<string, QueryParams[]> = {
	[ROUTES.HOME_PAGE]: [QueryParams.resourceAttributes],
	[ROUTES.LOGIN]: [QueryParams.resourceAttributes],
	[ROUTES.SOMETHING_WENT_WRONG]: [QueryParams.resourceAttributes],
	[ROUTES.UN_AUTHORIZED]: [QueryParams.resourceAttributes],
};
