import { combineReducers } from 'redux';

import appReducer from './app';
import globalTimeReducer from './global';

const reducers = combineReducers({
	globalTime: globalTimeReducer,
	app: appReducer,
});

export type AppState = ReturnType<typeof reducers>;

export default reducers;
