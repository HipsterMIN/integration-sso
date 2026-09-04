/**
 * idoInstance — IdO 게이트웨이 단일 채널 axios 인스턴스
 *
 * ADR-008 (FE 군 ↔ IdO 단일 채널 헌법) 의 코드 단면.
 * onepass-fe (및 향후 onepass-admin 등 모든 FE 군) 는 IdO 1 개 호스트로만 통신한다.
 * Q-IM / Q-Sign / agency-stub 은 어떤 FE 에서도 직접 호출하지 않는다.
 *
 * Phase 2 / SEC-IDO-01..05 (rename):
 *   - 환경변수: BE_API_ENDPOINT / BE_API_KEY → IDO_API_ENDPOINT / IDO_API_KEY
 *     (구 변수는 fallback 으로 한 페이즈 유지)
 *   - 헤더명:   X-BE-API-Key → X-IDO-API-Key
 *   - 식별자:   beInstance / beApiInstance → idoInstance / idoApiInstance
 *
 * Refs:
 *   - docs/internal/spec/02-architecture.md (ADR-008)
 *   - docs/internal/spec/03f-module-onepass-fe.md §3
 *   - docs/internal/spec/09-gap-and-roadmap.md §6.A (SEC-IDO-*)
 */
import axios from 'axios';

// IDO_API_* 우선, 없으면 구 BE_API_* fallback.
// webpack DefinePlugin 이 양쪽 키를 모두 process.env 에 노출하므로 빌드 환경에서 둘 다 평가 가능.
const IDO_BASE_URL: string =
	process.env.IDO_API_ENDPOINT || process.env.BE_API_ENDPOINT || '';

const IDO_API_KEY: string =
	process.env.IDO_API_KEY || process.env.BE_API_KEY || '';

const idoInstance = axios.create({
	baseURL: IDO_BASE_URL,
	headers: {
		'Content-Type': 'application/json',
		'X-IDO-API-Key': IDO_API_KEY,
	},
});

export default idoInstance;

export const idoApiInstance = axios.create({
	baseURL: IDO_BASE_URL,
	headers: {
		'Content-Type': 'application/json',
		'X-IDO-API-Key': IDO_API_KEY,
	},
});
