const BASE = '/images/onepass';
const RENEWAL_BASE = `${BASE}/renewal`;

const IMAGES = {
	LOGO: `${BASE}/logo.svg`,
	FOOTER_LOGO: `${BASE}/footer_logo.svg`,
	MEMBER: `${BASE}/img_member.png`,
	BUSINESS: `${BASE}/img_business.png`,
	CERT_PHONE: `${BASE}/ico_certi_phone.svg`,
	CERT_APP: `${BASE}/ico_certi_app.svg`,
	CERT_APP_BIG: `${BASE}/ico_certi_app_big.svg`,
	CERT_JOINT: `${BASE}/ico_certi_joint.svg`,
	CERT_JOINT_BIG: `${BASE}/ico_certi_joint_big.svg`,
	CERT_ANY: `${BASE}/ico_certi_any.svg`,

	// =============================================
	// 리뉴얼 (PUB260506) 자산 — public/images/onepass/renewal/
	// =============================================
	// 로고/이미지 (동일 파일명, 새 디자인으로 교체된 자산)
	RENEWAL_LOGO: `${RENEWAL_BASE}/logo.svg`,
	RENEWAL_FOOTER_LOGO: `${RENEWAL_BASE}/footer_logo.svg`,
	RENEWAL_MEMBER: `${RENEWAL_BASE}/img_member.png`,
	RENEWAL_BUSINESS: `${RENEWAL_BASE}/img_business.png`,
	RENEWAL_CERT_PHONE: `${RENEWAL_BASE}/ico_certi_phone.svg`,
	RENEWAL_CERT_PHONE_BIG: `${RENEWAL_BASE}/ico_certi_phone_big.svg`,
	RENEWAL_CERT_APP: `${RENEWAL_BASE}/ico_certi_app.svg`,
	RENEWAL_CERT_APP_BIG: `${RENEWAL_BASE}/ico_certi_app_big.svg`,
	RENEWAL_CERT_IPIN_BIG: `${RENEWAL_BASE}/ico_certi_ipin_big.svg`,
	RENEWAL_CERT_JOINT: `${RENEWAL_BASE}/ico_certi_joint.svg`,
	RENEWAL_CERT_JOINT_BIG: `${RENEWAL_BASE}/ico_certi_joint_big.svg`,
	RENEWAL_CERT_ANY: `${RENEWAL_BASE}/ico_certi_any.svg`,

	// 아이콘 (교체)
	RENEWAL_ICO_CHECK: `${RENEWAL_BASE}/ico_check.svg`,
	RENEWAL_ICO_CHECKBOX_CHECKED: `${RENEWAL_BASE}/ico_checkbox_checked.svg`,
	RENEWAL_ICO_CLOSE: `${RENEWAL_BASE}/ico_close.svg`,
	RENEWAL_ICO_HOME: `${RENEWAL_BASE}/ico_home.svg`,
	RENEWAL_ICO_SEARCH: `${RENEWAL_BASE}/ico_search.svg`,
	RENEWAL_ICO_ARROW_FORWARD: `${RENEWAL_BASE}/ico_arrow_forward.svg`,
	RENEWAL_ICO_ARROW_FORWARD_IOS: `${RENEWAL_BASE}/ico_arrow_forward_ios.svg`,
	RENEWAL_ICO_ARROW_RIGHT: `${RENEWAL_BASE}/ico_arrow_right.svg`,
	RENEWAL_ICO_ARROW_TOP: `${RENEWAL_BASE}/ico_arrow_top.svg`,

	// 아이콘 (신규 — 리뉴얼에서 처음 추가)
	RENEWAL_ICO_ACCOUNT_CIRCLE: `${RENEWAL_BASE}/ico_account_circle.svg`,
	RENEWAL_ICO_ARROW_BREADCRUMB: `${RENEWAL_BASE}/ico_arrow_breadcrumb.svg`,
	RENEWAL_ICO_ARROW_DROP_DOWN: `${RENEWAL_BASE}/ico_arrow_drop_down.svg`,
	RENEWAL_ICO_CHECK_CIRCLE: `${RENEWAL_BASE}/ico_check_circle.svg`,
	RENEWAL_ICO_KEYBOARD_CONTROL_KEY: `${RENEWAL_BASE}/ico_keyboard_control_key.svg`,
	RENEWAL_ICO_MODE_OFF_ON: `${RENEWAL_BASE}/ico_mode_off_on.svg`,

	// 페이지 이미지 (신규 — 리뉴얼에서 처음 추가)
	RENEWAL_PAGE_TITLE_IMG: `${RENEWAL_BASE}/page_title_img.png`,
	RENEWAL_SUB_CONVERSION_TITLE_IMG: `${RENEWAL_BASE}/sub_conversion_title_img.png`,
	RENEWAL_TEXT_LIST_IMG: `${RENEWAL_BASE}/text_list_img.png`,
	RENEWAL_TEXT_LIST_IMG_RECEIVE_NOTIFICATIONS: `${RENEWAL_BASE}/text_list_img_receive_notifications.png`,
	RENEWAL_WRITE_COMPLETED_IMG: `${RENEWAL_BASE}/write_completed_img.png`,
	RENEWAL_WRITE_COMPLETED_IMG_2: `${RENEWAL_BASE}/write_completed_img-2.png`,
	RENEWAL_WRITE_COMPLETED_IMG_MODAL: `${RENEWAL_BASE}/write_completed_img_modal.png`,
	RENEWAL_LIST_COMPLETED_IMG_MODAL: `${RENEWAL_BASE}/list_completed_img_modal.png`,
	RENEWAL_MYPAGE_NAV_BG: `${RENEWAL_BASE}/mypage_nav_bg.png`,
} as const;

export default IMAGES;
