import { useState } from 'react';

import PersonalAuthTab from './PersonalAuthTab';
import BusinessAuthTab from './BusinessAuthTab';
import PhoneAuthTab from './PhoneAuthTab';

type AuthTab = 'personal' | 'business' | 'phone';

function OacxTest(): JSX.Element {
	const [activeTab, setActiveTab] = useState<AuthTab>('personal');

	return (
		<div style={{ maxWidth: 700, margin: '40px auto', padding: '0 20px', fontFamily: 'sans-serif' }}>
			<h2>인증 테스트</h2>

			{/* 탭 UI */}
			<div style={{ display: 'flex', gap: 0, marginBottom: 24 }}>
				<button
					type="button"
					onClick={(): void => setActiveTab('personal')}
					style={{
						...tabStyle,
						...(activeTab === 'personal' ? activeTabStyle : inactiveTabStyle),
					}}
				>
					개인인증
				</button>
				<button
					type="button"
					onClick={(): void => setActiveTab('business')}
					style={{
						...tabStyle,
						...(activeTab === 'business' ? activeTabStyle : inactiveTabStyle),
					}}
				>
					기업인증
				</button>
				<button
					type="button"
					onClick={(): void => setActiveTab('phone')}
					style={{
						...tabStyle,
						...(activeTab === 'phone' ? activeTabStyle : inactiveTabStyle),
					}}
				>
					휴대폰인증
				</button>
			</div>

			{activeTab === 'personal' && <PersonalAuthTab />}
			{activeTab === 'business' && <BusinessAuthTab />}
			{activeTab === 'phone' && <PhoneAuthTab />}
		</div>
	);
}

const tabStyle: React.CSSProperties = {
	padding: '10px 28px',
	fontSize: 15,
	fontWeight: 600,
	cursor: 'pointer',
	borderWidth: 1,
	borderStyle: 'solid',
	borderColor: '#d1d5db',
	outline: 'none',
};

const activeTabStyle: React.CSSProperties = {
	background: '#2563eb',
	color: '#fff',
	borderColor: '#2563eb',
};

const inactiveTabStyle: React.CSSProperties = {
	background: '#fff',
	color: '#374151',
};

export default OacxTest;
