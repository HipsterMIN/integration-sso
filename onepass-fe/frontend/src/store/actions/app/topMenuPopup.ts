/* eslint-disable sonarjs/no-identical-functions */
import { Dispatch } from 'redux';
import AppActions from 'types/actions';

export const topMenuPopupToggle = (): ((
	dispatch: Dispatch<AppActions>,
) => void) => (dispatch: Dispatch<AppActions>): void => {
	dispatch({
		type: 'TOPMENU_POPUP_TOGGLE',
	});
};

export const topMenuPopup = (
	isOpen: boolean,
): ((dispatch: Dispatch<AppActions>) => void) => (
	dispatch: Dispatch<AppActions>,
): void => {
	dispatch({
		type: 'TOPMENU_POPUP',
		payload: isOpen,
	});
};
