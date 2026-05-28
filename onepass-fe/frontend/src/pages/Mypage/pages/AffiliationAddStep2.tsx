import getClients from 'api/ext/clients';
import { getEnterprise, getMember } from 'api/ext/members';
import {
	addAffiliation,
	addMemberAffiliation,
} from 'api/provision/affiliations';
import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import Spinner from 'components/Spinner';
import IMAGES from 'constants/images';
import history from 'lib/history';
import { useEffect, useState } from 'react';
import { Redirect } from 'react-router-dom';
import type { Client } from 'types/api/ext/clients';
import type { MemberClient } from 'types/api/ext/members';

import { loadSelectedServices } from './affiliationServices';
import { getMypageRoute } from './routes';
import {
	BusinessInfo,
	loadUserId,
	mapEnterpriseResponse,
	mapMemberResponse,
	MemberInfo,
	useInfoStore,
} from './useInfoStore';

async function fetchMatchedClients(selected: string[]): Promise<Client[]> {
	const res = await getClients();
	const all =
		res.statusCode === 200 && res.payload ? res.payload.data.clients : [];
	return all.filter((c) => selected.includes(c.ssoClientId));
}

/**
 * 회원 정보를 재조회해 context 를 갱신하고, 최신 clients 배열을 반환한다.
 * 항목별 성공/실패 판정은 응답 본문 구조에 의존하지 않고,
 * 갱신된 clients 에서 clientId 유무로 도출한다.
 */
async function refreshAndReturnClients(
	uuid: string,
	isBusiness: boolean,
	updateMember: (data: Partial<MemberInfo>) => void,
	updateBusiness: (data: Partial<BusinessInfo>) => void,
): Promise<MemberClient[]> {
	if (isBusiness) {
		const res = await getEnterprise(uuid);
		if (res.statusCode === 200 && res.payload?.data) {
			const mapped = mapEnterpriseResponse(res.payload.data);
			updateBusiness(mapped);
			return mapped.clients ?? [];
		}
	} else {
		const res = await getMember(uuid);
		if (res.statusCode === 200 && res.payload?.data) {
			const mapped = mapMemberResponse(res.payload.data);
			updateMember(mapped);
			return mapped.clients ?? [];
		}
	}
	return [];
}

function AffiliationAddStep2(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const affiliationRoute = getMypageRoute(memberType, 'AFFILIATION');
	const { updateMember, updateBusiness } = useInfoStore();

	const [processing, setProcessing] = useState(true);
	const [attemptedClients, setAttemptedClients] = useState<Client[]>([]);
	const [failedIdSet, setFailedIdSet] = useState<Set<string>>(new Set());
	const [isResultOpen, setIsResultOpen] = useState(false);
	const [shouldRedirect, setShouldRedirect] = useState(false);

	// 인증 직후 진입 → 선택된 ssoClientId 들로 추가 API 자동 호출
	// dev Strict Mode 에서는 두 번 실행되어 API 가 두 번 호출되지만, prod 에서는 한 번.
	// cleanup 의 cancelled flag 로 unmount 된 인스턴스의 setState 만 차단.
	useEffect(() => {
		let cancelled = false;
		(async (): Promise<void> => {
			const selected = loadSelectedServices();
			if (selected.length === 0) {
				if (!cancelled) setShouldRedirect(true);
				return;
			}

			const uuid = loadUserId(isBusiness ? 'business' : 'member');
			if (!uuid) {
				if (!cancelled) {
					setProcessing(false);
					setIsResultOpen(true);
				}
				return;
			}

			const matched = await fetchMatchedClients(selected);
			if (cancelled) return;
			setAttemptedClients(matched);

			const res = isBusiness
				? await addAffiliation(uuid, selected)
				: await addMemberAffiliation(uuid, selected);
			if (cancelled) return;

			// HTTP 단계 실패 → 시도한 항목 전체를 실패로 마킹
			if (res.statusCode !== 200) {
				setFailedIdSet(new Set(matched.map((m) => m.ssoClientId)));
				setProcessing(false);
				setIsResultOpen(true);
				return;
			}

			// 회원 정보 재조회 → 항목별 성공/실패 도출 (useYn 기준 — 'N' 이면 미연결=실패)
			const updated = await refreshAndReturnClients(
				uuid,
				isBusiness,
				updateMember,
				updateBusiness,
			);
			if (cancelled) return;
			const succeededIds = new Set(
				updated.filter((c) => c.useYn === 'Y').map((c) => c.clientId ?? c.clientNm),
			);
			const succeededNames = new Set(
				updated.filter((c) => c.useYn === 'Y').map((c) => c.clientNm),
			);
			const failed = new Set(
				matched
					.filter(
						(m) =>
							!succeededIds.has(m.ssoClientId)
							&& !succeededNames.has(m.clientNm),
					)
					.map((m) => m.ssoClientId),
			);

			setFailedIdSet(failed);
			setProcessing(false);
			setIsResultOpen(true);
		})();
		return (): void => {
			cancelled = true;
		};
		// 마운트 시 1회만 실행 — deps 비움
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	if (shouldRedirect) return <Redirect to={affiliationRoute} />;

	const goAffiliation = (): void => {
		history.push(affiliationRoute);
	};

	return (
		<MypageContent>
			{processing && (
				<Spinner tip="유관기관 계정 연결을 추가하고 있습니다..." height="300px" />
			)}

			<Modal
				id="modal_completed"
				isOpen={isResultOpen}
				onClose={goAffiliation}
				topText=""
				title="유관기관 목록"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						size: 'large',
						half: true,
						onClick: goAffiliation,
					},
				]}
			>
				<div className="completed-box">
					<figure className="img">
						<img
							src={IMAGES.RENEWAL_LIST_COMPLETED_IMG_MODAL}
							alt=""
							aria-hidden="true"
						/>
					</figure>
					<p className="completed-title">선택하신 유관기관의 계정 연결 추가</p>
					<ul className="text-list-wrap dots">
						{attemptedClients.map((c) => {
							const failed = failedIdSet.has(c.ssoClientId);
							return (
								<li key={c.ssoClientId}>
									<p>
										{c.clientNm}
										<span
											className={
												failed ? 'agency-fail-tag' : 'agency-success-tag'
											}
											style={{
												color: failed ? '#FE5D48' : '#22A06B',
												marginLeft: '.4rem',
											}}
										>
											{failed ? '(실패)' : '(성공)'}
										</span>
									</p>
								</li>
							);
						})}
					</ul>
				</div>
			</Modal>
		</MypageContent>
	);
}

export default AffiliationAddStep2;
