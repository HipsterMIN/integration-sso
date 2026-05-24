import ROUTES from 'constants/routes';
import history from 'lib/history';
import { FormEvent, useState } from 'react';

import SupportFrame from '../SupportFrame';

function SupportMain(): JSX.Element {
	const [agentId, setAgentId] = useState('');
	const [password, setPassword] = useState('');
	const [authMode, setAuthMode] = useState<'local' | 'future-sso'>('local');

	const onSubmit = (event: FormEvent): void => {
		event.preventDefault();
		if (!agentId.trim() || !password.trim()) {
			return;
		}
		history.push(ROUTES.SUPPORT_ADMIN);
	};

	return (
		<SupportFrame
			title="OnePass Support"
			description="onepass-support 독립 운영을 위한 최소 화면입니다."
		>
			<div className="support-grid">
				<section className="support-card">
					<h3 className="support-card-title">지원센터 로그인</h3>
					<form onSubmit={onSubmit} className="support-list">
						<label htmlFor="support-auth-mode" className="support-label">
							인증 모드
						</label>
						<select
							id="support-auth-mode"
							className="support-select"
							value={authMode}
							onChange={(event): void =>
								setAuthMode(event.target.value as 'local' | 'future-sso')
							}
						>
							<option value="local">CS 전용 계정</option>
							<option value="future-sso">통합인증 연계 (예정)</option>
						</select>

						<label htmlFor="support-agent-id" className="support-label">
							상담원 ID
						</label>
						<input
							id="support-agent-id"
							className="support-input"
							placeholder="예: cs-agent-01"
							value={agentId}
							onChange={(event): void => setAgentId(event.target.value)}
						/>

						<label htmlFor="support-password" className="support-label">
							비밀번호
						</label>
						<input
							id="support-password"
							type="password"
							className="support-input"
							placeholder="비밀번호"
							value={password}
							onChange={(event): void => setPassword(event.target.value)}
						/>

						<div className="support-actions">
							<button type="button" className="support-button secondary">
								계정 도움말
							</button>
							<button type="submit" className="support-button">
								로그인
							</button>
						</div>
					</form>
				</section>

				<section className="support-card">
					<h3 className="support-card-title">빠른 이동</h3>
					<div className="support-list">
						<div className="support-list-item">
							<div className="support-list-text">Q&A 접수 목록 확인</div>
							<button
								type="button"
								className="support-button secondary"
								onClick={(): void => history.push(ROUTES.SUPPORT_QNA)}
							>
								열기
							</button>
						</div>
						<div className="support-list-item">
							<div className="support-list-text">FAQ 안내 항목 점검</div>
							<button
								type="button"
								className="support-button secondary"
								onClick={(): void => history.push(ROUTES.SUPPORT_FAQ)}
							>
								열기
							</button>
						</div>
						<div className="support-list-item">
							<div className="support-list-text">관리자 콘솔 업무 큐</div>
							<button
								type="button"
								className="support-button secondary"
								onClick={(): void => history.push(ROUTES.SUPPORT_ADMIN)}
							>
								열기
							</button>
						</div>
					</div>
				</section>
			</div>
		</SupportFrame>
	);
}

export default SupportMain;
