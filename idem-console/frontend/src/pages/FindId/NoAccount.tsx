import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import history from 'lib/history';

import { loadLoginReturnSearch } from './services';

// 아이디 찾기 — 가입된 아이디 없음 안내 페이지
function FindIdNoAccount(): JSX.Element {
	// /login? 진입 시 보존해 둔 Keycloak 컨텍스트(return_client, return_uri)를
	// 회원가입 링크에 다시 부착해 가입 완료 후 원래 로그인 흐름으로 복귀할 수 있도록 한다
	const buildRegisterUrl = (): string => {
		const params = new URLSearchParams(loadLoginReturnSearch());
		const returnClient = params.get('return_client');
		const returnUri = params.get('return_uri');
		const query = [
			returnClient ? `return_client=${returnClient}` : '',
			returnUri ? `return_uri=${encodeURIComponent(returnUri)}` : '',
		]
			.filter(Boolean)
			.join('&');
		return query ? `${ROUTES.REGISTER_STEP1}?${query}` : ROUTES.REGISTER_STEP1;
	};

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
						<img src={IMAGES.RENEWAL_PAGE_TITLE_IMG} alt="" aria-hidden="true" />
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
							<p className="completed-title">조회된 아이디가 없습니다</p>
							<p className="completed-text">
								해당 정보로 가입된 회원을 찾을 수 없습니다.
								<br />
								중기원패스 통합로그인을 이용하시려면 회원가입 후 사용해 주세요.
							</p>
						</div>
						<div className="btn-box" role="group" aria-label="페이지 이동">
							<button
								type="button"
								className="btn point"
								onClick={(): void => history.push(buildRegisterUrl())}
							>
								<span>회원가입 하기</span>
								<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
							</button>
						</div>
					</div>
				</div>
			</div>
		</main>
	);
}

export default FindIdNoAccount;
