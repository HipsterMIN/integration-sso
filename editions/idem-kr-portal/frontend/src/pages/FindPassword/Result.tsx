import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import history from 'lib/history';
import {
	clearLoginReturnSearch,
	loadLoginReturnSearch,
} from 'pages/FindId/services';
import { useEffect } from 'react';

import { clearAll, isDone } from './services';

// PUB260513 find_password_step3.html — 비밀번호 변경 완료 화면
function FindPasswordResult(): JSX.Element {
	useEffect(() => {
		// step2 를 거치지 않고 직접 진입하면 step1 으로 복귀
		if (!isDone()) {
			history.replace(ROUTES.FIND_PASSWORD);
			return undefined;
		}
		return (): void => {
			clearAll();
		};
	}, []);

	return (
		<main id="main-content" className="container sub find_password step3">
			<div className="sub-body inner">
				<div className="page-title-wrap">
					<div className="page-title-text-box">
						<h2 className="page-title">중기원패스 회원 전환</h2>
						<p className="page-text">
							하나의 아이디로 중소벤처기업부 유관기관의 서비스를 모두 이용해보세요!
						</p>
					</div>
					<figure className="img-box">
						<img src={IMAGES.RENEWAL_PAGE_TITLE_IMG} alt="" aria-hidden="true" />
					</figure>
				</div>
				<div className="form-container">
					<div className="white-wrap">
						<div className="completed-box">
							<figure className="img">
								<img
									src={IMAGES.RENEWAL_WRITE_COMPLETED_IMG}
									alt=""
									aria-hidden="true"
								/>
							</figure>
							<p className="completed-title">비밀번호가 정상적으로 변경되었습니다</p>
							<p className="completed-text">
								비밀번호가 변경되었습니다.
								<br />
								새로운 비밀번호를 사용하여 로그인해주세요.
							</p>
						</div>
						<div className="btn-box" role="group" aria-label="페이지 이동">
							<button
								type="button"
								className="btn point"
								onClick={(): void => {
									// find-id 와 동일 — 진입 시점에 보존했던 Keycloak 파라미터를
									// 다시 부착해 /login 으로 복귀
									const search = loadLoginReturnSearch();
									clearLoginReturnSearch();
									history.push(`${ROUTES.LOGIN}${search}`);
								}}
							>
								<span>로그인 하기</span>
								<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
							</button>
						</div>
					</div>
				</div>
			</div>
		</main>
	);
}

export default FindPasswordResult;
