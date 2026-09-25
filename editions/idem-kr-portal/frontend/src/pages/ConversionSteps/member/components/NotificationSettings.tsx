interface NotificationSettingsProps {
	notifications: Record<string, boolean>;
	onToggle: (key: string) => void;
}

function NotificationSettings({ notifications, onToggle }: NotificationSettingsProps): JSX.Element {
	return (
		<div className="check-box-wrap" role="group" aria-label="알림 수신 방법 선택">
			<label className="check-box style4 large">
				<input
					type="checkbox"
					name="push"
					value="문자"
					checked={!!notifications['sms']}
					onChange={(): void => onToggle('sms')}
				/>
				<small>문자</small>
			</label>
			<label className="check-box style4 large">
				<input
					type="checkbox"
					name="push"
					value="알림톡(카카오톡)"
					checked={!!notifications['kakao']}
					onChange={(): void => onToggle('kakao')}
				/>
				<small>알림톡(카카오톡)</small>
			</label>
			<label className="check-box style4 large">
				<input
					type="checkbox"
					name="push"
					value="이메일수신"
					checked={!!notifications['email']}
					onChange={(): void => onToggle('email')}
				/>
				<small>이메일수신</small>
			</label>
		</div>
	);
}

export default NotificationSettings;
