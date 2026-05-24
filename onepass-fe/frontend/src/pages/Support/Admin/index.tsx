import SupportFrame from '../SupportFrame';

const queueItems = [
	{
		ticketId: 'TICKET-240524-021',
		channel: 'PHONE',
		status: 'OPEN',
		assignee: 'cs-agent-03',
		title: '전화 민원: 본인확인 코드 오류',
	},
	{
		ticketId: 'TICKET-240524-022',
		channel: 'QNA',
		status: 'PENDING',
		assignee: 'cs-agent-01',
		title: 'Q&A: 계정 정지 해제 요청',
	},
	{
		ticketId: 'TICKET-240524-023',
		channel: 'QNA',
		status: 'ESCALATED',
		assignee: 'cs-lead-01',
		title: 'Q&A: 기업 전환 검증 실패',
	},
];

function SupportAdmin(): JSX.Element {
	return (
		<SupportFrame
			title="Support Admin Console"
			description="CS 업무 큐와 내부 메모를 처리하는 최소 화면입니다."
		>
			<section className="support-card">
				<h3 className="support-card-title">티켓 큐</h3>
				<div className="support-list">
					{queueItems.map((item) => (
						<div className="support-list-item" key={item.ticketId}>
							<div>
								<div className="support-list-text">{item.title}</div>
								<div className="support-list-meta">
									{item.ticketId} | {item.channel}
								</div>
							</div>
							<div>
								<div className="support-tag">{item.status}</div>
								<div className="support-list-meta">{item.assignee}</div>
							</div>
						</div>
					))}
				</div>
			</section>

			<div className="support-grid">
				<section className="support-card">
					<h3 className="support-card-title">전화 민원 정리</h3>
					<div className="support-list">
						<label className="support-label" htmlFor="support-call-ticket-id">
							티켓 ID
						</label>
						<input
							id="support-call-ticket-id"
							className="support-input"
							placeholder="예: TICKET-240524-021"
						/>
						<label className="support-label" htmlFor="support-call-summary">
							상담 요약
						</label>
						<textarea
							id="support-call-summary"
							className="support-textarea"
							placeholder="전화 상담 요약 내용"
						/>
						<div className="support-actions">
							<button type="button" className="support-button">
								민원 기록
							</button>
						</div>
					</div>
				</section>

				<section className="support-card">
					<h3 className="support-card-title">내부 메모 / 상태 변경</h3>
					<div className="support-list">
						<label className="support-label" htmlFor="support-note-ticket-id">
							티켓 ID
						</label>
						<input
							id="support-note-ticket-id"
							className="support-input"
							placeholder="예: TICKET-240524-022"
						/>
						<label className="support-label" htmlFor="support-note-status">
							상태
						</label>
						<select id="support-note-status" className="support-select">
							<option>OPEN</option>
							<option>PENDING</option>
							<option>ANSWERED</option>
							<option>ESCALATED</option>
							<option>CLOSED</option>
						</select>
						<label className="support-label" htmlFor="support-note-content">
							내부 메모
						</label>
						<textarea
							id="support-note-content"
							className="support-textarea"
							placeholder="내부 공유 메모"
						/>
						<div className="support-actions">
							<button type="button" className="support-button secondary">
								메모 추가
							</button>
							<button type="button" className="support-button">
								상태 저장
							</button>
						</div>
					</div>
				</section>
			</div>
		</SupportFrame>
	);
}

export default SupportAdmin;
