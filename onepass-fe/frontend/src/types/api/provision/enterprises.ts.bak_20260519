export interface NewPic {
	memberName: string;
	loginId: string;
	initialPassword: string;
	email: string;
	phone: string;
}

export interface ClientLink {
	clientId: string;
	mbrId?: string;
	rprsInstYn?: string;
}

export interface Props {
	bzmnTypeCd: string;
	brno: string;
	bzmnNm: string;
	rprsvNm: string;
	mbrId?: string;
	newPic: NewPic;
	clients?: ClientLink[];
}

export interface ProvisionResponseData {
	entMbrNo: string;
	entMbrUuid: string;
	brno: string;
	bzmnNm: string;
	picMbrNo: string;
	picMbrUuid: string;
	clients: { clientId: string; mbrId?: string }[];
	provisioningToken: string;
	status: string;
	tokenExpiresAt: string;
}

export interface PayloadProps {
	success: boolean;
	data: ProvisionResponseData;
}
