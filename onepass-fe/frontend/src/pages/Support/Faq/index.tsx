import SupportFrame from '../SupportFrame';

const faqItems = [
	{
		group: '계정',
		question: '비밀번호를 잊어버렸습니다.',
		answer: '로그인 화면의 비밀번호 찾기를 통해 재설정합니다.',
	},
	{
		group: '본인확인',
		question: '인증 수단 선택이 보이지 않습니다.',
		answer: '브라우저 캐시 초기화 후 다시 접속해 주세요.',
	},
	{
		group: '기업회원',
		question: '사업자 전환 상태를 어디서 보나요?',
		answer: '마이페이지 기업회원 메뉴에서 전환 상태를 확인합니다.',
	},
];

function SupportFaq(): JSX.Element {
	return (
		<SupportFrame
			title="Support FAQ"
			description="고객 안내용 FAQ를 점검하는 최소 화면입니다."
		>
			<section className="support-card">
				<h3 className="support-card-title">FAQ 목록</h3>
				<div className="support-list">
					{faqItems.map((item) => (
						<div className="support-list-item" key={item.question}>
							<div>
								<div className="support-list-text">{item.question}</div>
								<div className="support-list-meta">{item.answer}</div>
							</div>
							<div className="support-tag">{item.group}</div>
						</div>
					))}
				</div>
			</section>

			<section className="support-card">
				<h3 className="support-card-title">FAQ 항목 추가</h3>
				<div className="support-list">
					<label className="support-label" htmlFor="support-faq-group">
						그룹
					</label>
					<input
						id="support-faq-group"
						className="support-input"
						placeholder="예: 계정"
					/>
					<label className="support-label" htmlFor="support-faq-question">
						질문
					</label>
					<input
						id="support-faq-question"
						className="support-input"
						placeholder="자주 묻는 질문"
					/>
					<label className="support-label" htmlFor="support-faq-answer">
						답변
					</label>
					<textarea
						id="support-faq-answer"
						className="support-textarea"
						placeholder="고객 안내 답변"
					/>
					<div className="support-actions">
						<button type="button" className="support-button secondary">
							초기화
						</button>
						<button type="button" className="support-button">
							저장
						</button>
					</div>
				</div>
			</section>
		</SupportFrame>
	);
}

export default SupportFaq;
