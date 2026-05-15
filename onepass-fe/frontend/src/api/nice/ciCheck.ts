/**
 * NICE CI 기반 회원 확인 API (조회 전용)
 *
 * @description
 * POST /api/v1/auth/nice/ci-check — ido 백엔드에서 Q-IM CI 매핑을 조회하여
 * 해당 CI의 회원 존재 여부 및 회원 ID를 반환한다.
 *
 * 백엔드 엔드포인트: AuthController.niceCiCheck() → AuthService.checkNiceCi()
 *
 * @remarks
 * - 로그인 플로우와 무관: Login/index.tsx의 NICE 휴대폰 인증은 encCi를 직접
 *   form POST(IND_CI)로 Q-Sign에 전달하므로 이 API를 거치지 않음
 * - 설계 목적: 전환(ConversionSteps) 또는 마이페이지에서 CI 기반 기존 계정
 *   매핑 확인이 필요한 시나리오에 사용 예정
 *
 * TODO(ci-check-fe): 아래 시나리오 중 하나가 확정되면 연결 필요:
 *   1. 전환 Step에서 기존 CI 매핑 계정 존재 여부 사전 확인 (중복 가입 방지)
 *   2. 마이페이지 본인인증 변경 플로우에서 CI 재검증
 *   현재(v0.8.8): 미연결 상태 — Q2=B PoC 완료 후 별도 Sprint에서 연결 예정
 *
 * @see ido/auth/controller/AuthController.java — niceCiCheck()
 * @see ido/auth/service/AuthService.java — checkNiceCi()
 * @see types/api/nice/ciCheck.ts — CiCheckRequest, CiCheckResponse
 */
import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { beApiInstance } from 'api/beInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type { CiCheckRequest, CiCheckResponse } from 'types/api/nice/ciCheck';

const ciCheck = async (
	params: CiCheckRequest,
): Promise<SuccessResponse<CiCheckResponse> | ErrorResponse> => {
	try {
		const response = await beApiInstance.post('/api/v1/auth/nice/ci-check', params);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default ciCheck;
