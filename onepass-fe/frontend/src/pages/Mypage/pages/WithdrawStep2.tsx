import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import history from 'lib/history';
import { FormEvent } from 'react';

import { getMypageRoute } from './routes';

// 통합회원 탈퇴 — step2 (URL: /withdraw/step2): 인증 카드 선택
// AffiliationWithdrawStep1 / InformationStep2 와 동일 패턴 (기업/개인 인증서 + 간편인증서 카드 2장)
function BusinessAuth({ onNext }: { onNext: string }): JSX.Element {
	const goNext = (): void => history.push(onNext);

	return (
		<>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>중기원패스를 이용해 주신 회원님께 진심으로 감사드립니다.</p>
						</li>
						<li>
							<p>
								탈퇴 이후에 재가입은 가능하지만 기존에 사용하였던 ID는 더이상
								사용할 수 없습니다.
							</p>
						</li>
						<li>
							<p>기업회원은 해당 기업관리자만이 회원탈퇴가 가능합니다.</p>
						</li>
					</ul>
					<figure className="img">
						<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
					</figure>
				</div>
			</div>
			<form
				className="form-container"
				onSubmit={(e: FormEvent): void => e.preventDefault()}
				aria-label="기업 인증"
			>
				<div className="white-wrap">
					<div className="title-box">
						<h3 className="h3-title">기업 인증</h3>
						<p className="text">회원 탈퇴 시 기업 인증 후 진행해 주시기 바랍니다</p>
					</div>
					<div className="check-box-wrap" role="group" aria-label="인증 수단 선택">
						<button type="button" className="check-box style2" onClick={goNext}>
							<div className="right-box">
								<figure>
									<img src={IMAGES.RENEWAL_CERT_JOINT_BIG} alt="" aria-hidden="true" />
								</figure>
								<div className="text-box">
									<strong className="tit">기업인증서</strong>
									<p className="text">
										공동인증서(구 공인인증서) 또는 금융인증서를 활용하여 기업 정보를
										안전하고 확실하게 인증합니다
									</p>
								</div>
							</div>
						</button>
						<button type="button" className="check-box style2" onClick={goNext}>
							<div className="right-box">
								<figure>
									<img src={IMAGES.RENEWAL_CERT_APP_BIG} alt="" aria-hidden="true" />
								</figure>
								<div className="text-box">
									<strong className="tit">사업자 간편인증서</strong>
									<p className="text">
										별도의 보안 프로그램 설치 없이 네이버, 카카오, PASS 등 간편인증
										수단으로 사업자 여부를 빠르게 확인하여 인증합니다
									</p>
								</div>
							</div>
						</button>
					</div>
				</div>
			</form>
		</>
	);
}

function MemberAuth({ onNext }: { onNext: string }): JSX.Element {
	const goNext = (): void => history.push(onNext);

	return (
		<>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>중기원패스를 이용해 주신 회원님께 진심으로 감사드립니다.</p>
						</li>
						<li>
							<p>
								탈퇴 이후에 재가입은 가능하지만 기존에 사용하였던 ID는 더이상
								사용할 수 없습니다.
							</p>
						</li>
					</ul>
					<figure className="img">
						<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
					</figure>
				</div>
			</div>
			<form
				className="form-container"
				onSubmit={(e: FormEvent): void => e.preventDefault()}
				aria-label="개인 인증"
			>
				<div className="white-wrap">
					<div className="title-box">
						<h3 className="h3-title">개인 인증</h3>
						<p className="text">회원 탈퇴 시 본인 인증 후 진행해 주시기 바랍니다</p>
					</div>
					<div className="check-box-wrap" role="group" aria-label="인증 수단 선택">
						<button type="button" className="check-box style2" onClick={goNext}>
							<div className="right-box">
								<figure>
									<img src={IMAGES.RENEWAL_CERT_JOINT_BIG} alt="" aria-hidden="true" />
								</figure>
								<div className="text-box">
									<strong className="tit">개인인증서</strong>
									<p className="text">
										공동인증서(구 공인인증서) 또는 금융인증서를 활용하여 개인 정보를
										안전하고 확실하게 인증합니다
									</p>
								</div>
							</div>
						</button>
						<button type="button" className="check-box style2" onClick={goNext}>
							<div className="right-box">
								<figure>
									<img src={IMAGES.RENEWAL_CERT_APP_BIG} alt="" aria-hidden="true" />
								</figure>
								<div className="text-box">
									<strong className="tit">개인 간편인증서</strong>
									<p className="text">
										별도의 보안 프로그램 설치 없이 네이버, 카카오, PASS 등 간편인증
										수단으로 본인 여부를 빠르게 확인하여 인증합니다
									</p>
								</div>
							</div>
						</button>
						<button type="button" className="check-box style2" onClick={goNext}>
							<div className="right-box">
								<figure>
									<img src={IMAGES.RENEWAL_CERT_PHONE} alt="" aria-hidden="true" />
								</figure>
								<div className="text-box">
									<strong className="tit">휴대폰 인증</strong>
									<p className="text">
										본인 명의의 휴대폰으로 인증번호를 받아 빠르게 본인 여부를 확인합니다
									</p>
								</div>
							</div>
						</button>
						<button type="button" className="check-box style2" onClick={goNext}>
							<div className="right-box">
								<figure>
									<img src={IMAGES.RENEWAL_CERT_ANY} alt="" aria-hidden="true" />
								</figure>
								<div className="text-box">
									<strong className="tit">Any-ID</strong>
									<p className="text">
										공공 디지털 서비스 통합 인증 (Any-ID) 으로 본인 여부를 확인합니다
									</p>
								</div>
							</div>
						</button>
					</div>
				</div>
			</form>
		</>
	);
}

function WithdrawStep2(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const completeRoute = getMypageRoute(memberType, 'WITHDRAW_COMPLETE');

	return (
		<MypageContent>
			{isBusiness ? (
				<BusinessAuth onNext={completeRoute} />
			) : (
				<MemberAuth onNext={completeRoute} />
			)}
		</MypageContent>
	);
}

export default WithdrawStep2;
