import { ConsentCatalog } from './ConsentCatalog';

/** 1.1 동의 카탈로그 — 플랫폼 공통 항목 (전역 관리자 메뉴 "동의 항목"). 서비스 전용 항목은 기관 상세의 카드. */
export function Consents() {
  return <ConsentCatalog title="동의 항목 — 플랫폼 공통 (1.1)" />;
}
