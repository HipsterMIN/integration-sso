import setLocalStorageKey from 'api/browser/localstorage/set';
import { IS_SIDEBAR_HIDED } from 'constants/app';
import { Dispatch } from 'redux';
import AppActions from 'types/actions';

export const sideBarHide = (
	hideState: boolean,
): ((dispatch: Dispatch<AppActions>) => void) => {
	setLocalStorageKey(IS_SIDEBAR_HIDED, `${hideState}`);
	return (dispatch: Dispatch<AppActions>): void => {
		dispatch({
			type: 'SIDEBAR_HIDE',
			payload: hideState,
		});
	};
};
