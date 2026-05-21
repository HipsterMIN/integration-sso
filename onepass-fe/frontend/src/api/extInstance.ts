/**
 * extInstance — Q-IM External API 래퍼
 *
 * B-5 보안 패치:
 *   - 기존: EXT_API_ENDPOINT(Q-IM 직접) + EXT_API_KEY FE 번들 노출
 *   - 변경: ido(8083) /api/ext/** forward proxy 경유
 *           서버사이드에서 X-Ext-Api-Key 주입 (FE 번들 미포함)
 *
 * @deprecated 신규 코드에서는 beApiInstance를 직접 사용하세요.
 *             기존 ext/* 파일과의 하위호환을 위해 beApiInstance를 re-export합니다.
 */
import { beApiInstance } from 'api/beInstance';

export { beApiInstance as default };
