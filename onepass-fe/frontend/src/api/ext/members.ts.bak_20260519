import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type { AffiliationsResponse, EnterpriseModifyRequest, EnterpriseResponse, MemberModifyRequest, MemberResponse } from 'types/api/ext/members';

/** 개인회원 조회 (§5.1) */
export const getMember = async (
	mbrNo: string,
): Promise<SuccessResponse<MemberResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.get(`/api/ext/members/${mbrNo}`);
		return {
			statusCode: 200,
			error: null,
			message: 'success',
			payload: response.data,
		};
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

/** 개인회원 유관서비스 목록 조회 (§5.5) */
export const getMemberAffiliations = async (
	mbrUuid: string,
): Promise<SuccessResponse<AffiliationsResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.get(`/api/ext/members/${mbrUuid}/affiliations`);
		return {
			statusCode: 200,
			error: null,
			message: 'success',
			payload: response.data,
		};
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

/** 기업회원 유관서비스 목록 조회 (§5.6) */
export const getEnterpriseAffiliations = async (
	mbrUuid: string,
): Promise<SuccessResponse<AffiliationsResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.get(`/api/ext/enterprises/${mbrUuid}/affiliations`);
		return {
			statusCode: 200,
			error: null,
			message: 'success',
			payload: response.data,
		};
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

/** 기업회원 조회 (§5.2) */
export const getEnterprise = async (
	entMbrNo: string,
): Promise<SuccessResponse<EnterpriseResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.get(`/api/ext/enterprises/${entMbrNo}`);
		return {
			statusCode: 200,
			error: null,
			message: 'success',
			payload: response.data,
		};
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

/** 기업회원 정보 수정 (POST /api/ext/provision/enterprises/modify_local) */
export const modifyEnterprise = async (
	body: EnterpriseModifyRequest,
): Promise<SuccessResponse<EnterpriseResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.post(
			'/api/ext/provision/enterprises/modify_local',
			body,
		);
		return {
			statusCode: 200,
			error: null,
			message: 'success',
			payload: response.data,
		};
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

/** 개인회원 정보 수정 (POST /api/ext/provision/users/modify_local) */
export const modifyMember = async (
	body: MemberModifyRequest,
): Promise<SuccessResponse<MemberResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.post(
			'/api/ext/provision/users/modify_local',
			body,
		);
		return {
			statusCode: 200,
			error: null,
			message: 'success',
			payload: response.data,
		};
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};
