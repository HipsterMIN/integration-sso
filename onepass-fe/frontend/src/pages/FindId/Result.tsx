import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import history from 'lib/history';
import { useEffect } from 'react';

import {
	clearFoundLoginId,
	clearLoginReturnSearch,
	loadFoundLoginId,
	loadLoginReturnSearch,
} from './services';

// PUB260513 find_id_step2.html — 아이디 찾기 결과 화면
function FindIdResult(): JSX.Element {
	const loginId = loadFoundLoginId();

	useEffect(() => {
		// 새로고침/뒤로가기로 인증 없이 접근하면 step1 으로 복귀
		if (!loginId) {
			history.replace(ROUTES.FIND_ID);
		}
		return (): void => {
			clearFoundLoginId();
		};
	}, [loginId]);

	return (
		<main id="main-content" className="container sub find_id step2 member">
			<div className="sub-body inner">
				<div className="page-title-wrap">
					<div className="page-title-text-box">
						<h2 className="page-title">중기원패스 회원 전환</h2>
						<p className="page-text">
							하나의 아이디로 중소벤처기업부 유관기관의 서비스를 모두 이용해보세요!
						</p>
					</div>
					<figure className="img-box">
						<img
							src={IMAGES.RENEWAL_PAGE_TITLE_IMG}
							alt=""
							aria-hidden="true"
						/>
					</figure>
				</div>
				<div className="form-container">
					<div className="form-wrap white-wrap">
						<div className="completed-box">
							<figure className="img">
								<img
									src={IMAGES.RENEWAL_WRITE_COMPLETED_IMG}
									alt=""
									aria-hidden="true"
								/>
							</figure>
							<p className="completed-title">
								회원님의 아이디는 <span className="gray">{loginId}</span> 로 등록되어
								있습니다
							</p>
							<p className="completed-text">
								비밀번호가 기억나지 않으실 경우{' '}
								<button
									type="button"
									className="btn text"
									onClick={(): void => history.push(ROUTES.FIND_PASSWORD)}
								>
									<span>비밀번호 재설정</span>
								</button>
								{' '}에서 확인하시기 바랍니다.
							</p>
						</div>
						<div
							className="btn-box"
							role="group"
							aria-label="페이지 이동"
						>
							<button
								type="button"
								className="btn point"
								onClick={(): void => {
									// 진입 시점에 보존했던 Keycloak 파라미터(action_url, return_uri 등)를
									// 다시 부착해 /login 으로 복귀
									const search = loadLoginReturnSearch();
									clearLoginReturnSearch();
									history.push(`${ROUTES.LOGIN}${search}`);
								}}
							>
								<span>로그인 하기</span>
								<i
									className="icon ico-arrow-forward-ios small"
									aria-hidden="true"
								/>
							</button>
						</div>
					</div>
				</div>
			</div>
		</main>
	);
}

export default FindIdResult;
