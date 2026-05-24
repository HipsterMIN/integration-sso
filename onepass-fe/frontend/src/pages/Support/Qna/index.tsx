import SupportFrame from '../SupportFrame';

const qnaItems = [
	{
		id: 'QNA-240524-001',
		title: '계정 잠금 해제 요청',
		status: 'OPEN',
		owner: '미배정',
		updatedAt: '2026-05-24 15:20',
	},
	{
		id: 'QNA-240524-002',
		title: '본인확인 실패 문의',
		status: 'PENDING',
		owner: 'cs-agent-02',
		updatedAt: '2026-05-24 14:57',
	},
	{
		id: 'QNA-240524-003',
		title: '사업자 전환 중 오류',
		status: 'ANSWERED',
		owner: 'cs-lead-01',
		updatedAt: '2026-05-24 14:41',
	},
];

function SupportQna(): JSX.Element {
	return (
		<SupportFrame
			title="Support Q&A"
			description="문의 접수와 답변 처리를 확인하는 최소 화면입니다."
		>
			<section className="support-card">
				<h3 className="support-card-title">Q&A 접수 목록</h3>
				<div className="support-list">
					{qnaItems.map((item) => (
						<div className="support-list-item" key={item.id}>
							<div>
								<div className="support-list-text">{item.title}</div>
								<div className="support-list-meta">{item.id}</div>
							</div>
							<div>
								<div className="support-tag">{item.status}</div>
								<div className="support-list-meta">{item.owner}</div>
								<div className="support-list-meta">{item.updatedAt}</div>
							</div>
						</div>
					))}
				</div>
			</section>

			<section className="support-card">
				<h3 className="support-card-title">답변 작성</h3>
				<div className="support-list">
					<label className="support-label" htmlFor="support-qna-reply-id">
						문의 ID
					</label>
					<input
						id="support-qna-reply-id"
						className="support-input"
						placeholder="예: QNA-240524-001"
					/>
					<label className="support-label" htmlFor="support-qna-reply-content">
						답변 내용
					</label>
					<textarea
						id="support-qna-reply-content"
						className="support-textarea"
						placeholder="고객에게 전달할 답변"
					/>
					<div className="support-actions">
						<button type="button" className="support-button secondary">
							임시저장
						</button>
						<button type="button" className="support-button">
							답변 등록
						</button>
					</div>
				</div>
			</section>
		</SupportFrame>
	);
}

export default SupportQna;
