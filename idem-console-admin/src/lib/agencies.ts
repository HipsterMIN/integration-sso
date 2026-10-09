// 1.1.1 G1-3 기관 목록 — 서버 페이징·검색 쿼리(GET /agencies?page&size&q, 응답 봉투 {items,page,size,total}). 한 페이지 50, hub 최대 200.
export const PAGE_SIZE = 50;

/** 빈 검색어는 붙이지 않는다; page 는 0 아래로 내려가지 않는다 */
export const agenciesQuery = (page: number, q: string, size = PAGE_SIZE): string =>
  `/agencies?page=${Math.max(0, Math.trunc(page))}&size=${Math.min(200, Math.max(1, Math.trunc(size)))}${q.trim() ? `&q=${encodeURIComponent(q.trim())}` : ''}`;

/** 전체 쪽 수 — total 0 이면 1 */
export const pageCount = (total: number, size = PAGE_SIZE): number => (size > 0 ? Math.max(1, Math.ceil(total / size)) : 1);
