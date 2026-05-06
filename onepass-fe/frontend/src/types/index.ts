// ══════════════════════════════════════════════════════════════════════════════
// OnePass FE — 공통 타입 정의
// ══════════════════════════════════════════════════════════════════════════════

/** 인증 수준 */
export type AuthLevel = 'L1' | 'L2' | 'L3';

/** FE 세션 상태 (BFF /api/v1/session/check 응답) */
export interface SessionCheckResponse {
  valid: boolean;
  qimUserId?: string;
  authLevel?: AuthLevel;
}

/** 로그인 / 전환 흐름 상태 */
export type ConversionStep =
  | 'STEP1_INTRO'
  | 'STEP2_ID_VERIFY'
  | 'STEP3_AUTH_SELECT'
  | 'STEP4_AUTH_EXECUTE'
  | 'STEP5_RESULT'
  | 'STEP6_REDIRECT'
  | 'STEP7_COMPLETE';

/** 공통 API 에러 응답 */
export interface ApiError {
  code: string;
  message: string;
  traceId?: string;
}

/** Handoff 발행 요청 (BFF → IdO) */
export interface HandoffIssueRequest {
  agencyCode: string;
  returnUrl: string;
  authLevel?: AuthLevel;
}

/** Handoff 발행 응답 */
export interface HandoffIssueResponse {
  handoffUrl: string;
  ticketId: string;
  expiresAt: string;
}
