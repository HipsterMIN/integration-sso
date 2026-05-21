/**
 * k6 공통 헬퍼 — OnePass Platform 부하 테스트
 *
 * 공유 상수, 헤더 빌더, 응답 검증, 메트릭 태깅 유틸리티를 제공한다.
 */

import { check, fail } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ── 환경 설정 ──────────────────────────────────────────────────────────────
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';

/** 기관 코드 (agency-stub PoC 기관) */
export const AGENCY_CODE = __ENV.AGENCY_CODE || 'AGENCY001';

/** X-Internal-Api-Key (handoff 발급용) */
export const INTERNAL_API_KEY = __ENV.INTERNAL_API_KEY || 'test-internal-key';

// ── 공통 커스텀 메트릭 ─────────────────────────────────────────────────────
/** 비즈니스 오류율 (HTTP 200이지만 resultCode != 2000) */
export const bizErrorRate = new Rate('biz_error_rate');

/** 엔드포인트별 응답시간 트렌드 */
export const handoffIssueTrend    = new Trend('handoff_issue_duration', true);
export const handoffVerifyTrend   = new Trend('handoff_verify_duration', true);
export const niceUrlTrend         = new Trend('nice_phone_url_duration', true);
export const niceResultTrend      = new Trend('nice_phone_result_duration', true);
export const ciCheckTrend         = new Trend('nice_ci_check_duration', true);
export const oacxAccessTrend      = new Trend('oacx_access_info_duration', true);
export const oacxEasysignTrend    = new Trend('oacx_easysign_duration', true);
export const rateLimitHitCounter  = new Counter('rate_limit_429_total');

// ── 헤더 빌더 ──────────────────────────────────────────────────────────────

/**
 * 기본 JSON 헤더 생성
 * @param {string} [correlationId] - X-Correlation-Id 값 (없으면 자동 생성)
 * @returns {Object} HTTP 헤더 객체
 */
export function jsonHeaders(correlationId) {
  return {
    'Content-Type':    'application/json',
    'Accept':          'application/json',
    'X-Correlation-Id': correlationId || `k6-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  };
}

/**
 * 내부 API 헤더 (Handoff 발급 전용)
 */
export function internalHeaders(correlationId) {
  return Object.assign(jsonHeaders(correlationId), {
    'X-Internal-Api-Key': INTERNAL_API_KEY,
    'X-Agency-Code':      AGENCY_CODE,
  });
}

// ── 응답 검증 헬퍼 ────────────────────────────────────────────────────────

/**
 * HTTP 응답 표준 검증
 * - 상태 코드, 응답 시간, 비즈니스 resultCode를 일괄 체크
 *
 * @param {Object} res         - k6 Response 객체
 * @param {string} name        - 검증 태그 이름 (메트릭 레이블)
 * @param {Object} [opts]      - 옵션
 * @param {number} [opts.expectedStatus=200]
 * @param {string} [opts.expectedResultCode='2000']
 * @param {number} [opts.maxDurationMs=2000]
 * @returns {boolean} 모든 검증 통과 여부
 */
export function assertResponse(res, name, opts = {}) {
  const expectedStatus     = opts.expectedStatus     ?? 200;
  const expectedResultCode = opts.expectedResultCode ?? '2000';
  const maxDurationMs      = opts.maxDurationMs      ?? 2000;

  // 429: Rate Limit 히트 카운터
  if (res.status === 429) {
    rateLimitHitCounter.add(1);
  }

  const ok = check(res, {
    [`${name} status ${expectedStatus}`]: (r) => r.status === expectedStatus,
    [`${name} duration < ${maxDurationMs}ms`]: (r) => r.timings.duration < maxDurationMs,
  });

  // 비즈니스 레이어 검증 (2xx 응답인 경우에만)
  if (res.status >= 200 && res.status < 300 && expectedResultCode) {
    let body;
    try { body = JSON.parse(res.body); } catch (_) { body = null; }

    const bizOk = body && body.resultCode === expectedResultCode;
    bizErrorRate.add(!bizOk ? 1 : 0);

    check(res, {
      [`${name} resultCode=${expectedResultCode}`]: () => bizOk,
    });

    return ok && bizOk;
  }

  return ok;
}

/**
 * Rate Limit(429) 응답 전용 검증
 */
export function assertRateLimited(res, name) {
  rateLimitHitCounter.add(1);
  return check(res, {
    [`${name} rate-limited 429`]: (r) => r.status === 429,
  });
}

// ── 더미 데이터 생성 ──────────────────────────────────────────────────────

/** 88자 더미 CI (NICE 규격) */
export function fakeCi() {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  return Array.from({ length: 88 }, () => chars[Math.floor(Math.random() * chars.length)]).join('');
}

/** 랜덤 기관 코드 (풀 중에서 선택) */
export function randomAgencyCode() {
  const pool = ['AGENCY001', 'AGENCY002', 'AGENCY003'];
  return pool[Math.floor(Math.random() * pool.length)];
}

/** ISO 8601 타임스탬프 */
export function now() {
  return new Date().toISOString();
}
