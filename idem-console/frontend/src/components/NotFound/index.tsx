import getLocalStorageKey from 'api/browser/localstorage/get';
import NotFoundImage from 'assets/NotFound';
import { LOCALSTORAGE } from 'constants/localStorage';
import ROUTES from 'constants/routes';
import { useCallback } from 'react';
import { useDispatch } from 'react-redux';
import { Link } from 'react-router-dom';
import { Dispatch } from 'redux';
import AppActions from 'types/actions';
import { LOGGED_IN } from 'types/actions/app';

import { defaultText } from './constant';
import './NotFound.styles.scss';

function NotFound({ text = defaultText }: Props): JSX.Element {
	const dispatch = useDispatch<Dispatch<AppActions>>();
	const isLoggedIn = getLocalStorageKey(LOCALSTORAGE.IS_LOGGED_IN);

	const onClickHandler = useCallback(() => {
		if (isLoggedIn) {
			dispatch({
				type: LOGGED_IN,
				payload: {
					isLoggedIn: true,
				},
			});
		}
	}, [dispatch, isLoggedIn]);

	return (
		<div className="notFoundContainer">
			<NotFoundImage />

			<div className="notFoundTextContainer">
				<p className="notFoundText">{text}</p>
				<p className="notFoundText">Page Not Found</p>
			</div>

			<Link className="notFoundButton" onClick={onClickHandler} to={ROUTES.HOME_PAGE} tabIndex={0}>
				Return To Services Page
			</Link>
		</div>
	);
}

interface Props {
	text?: string;
}

NotFound.defaultProps = {
	text: defaultText,
};

export default NotFound;
