import { AppAction } from './app';
import { GlobalTimeAction } from './globalTime';

type AppActions = AppAction | GlobalTimeAction;

export default AppActions;
