-- ═══════════════════════════════════════════════════════════════════════════
-- 1.1.2 G2-8 (성능 측정 중 발견, 플랜 §3 성능 보고): handoff_audit.auth_result_id 를 36 → 100 자로 넓힌다.
--   코어 로그인 프런트(1.1)는 본인확인 제공자의 txId 를 FE 세션의 authResultId 로 쓴다 — MOCK 은 'mock-'+UUID(41자),
--   SPI 제공자는 형식이 자유다. VARCHAR(36) 이라 INSERT 가 "value too long" 으로 실패했고('무시' 로그), 같은 @Transactional 발급
--   안의 outbox 적재(HANDOFF_ISSUED)까지 "current transaction is aborted" 로 따라 실패했다('비치명적' 로그). 발급 자체는 Redis 라
--   성공으로 끝나 CI 스모크·IT 가 잡지 못했다 — 감사 이력(handoff_audit)·기관 이벤트(outbox)가 조용히 유실되고 있었다.
--   correlation_id 는 TraceparentFilter 가 36자 규격으로 묶으므로 열은 그대로 둔다.
-- 되돌림: ALTER TABLE idem_hub.handoff_audit ALTER COLUMN auth_result_id TYPE VARCHAR(36); (100자 값이 있으면 실패)
-- ═══════════════════════════════════════════════════════════════════════════
ALTER TABLE idem_hub.handoff_audit
    ALTER COLUMN auth_result_id TYPE VARCHAR(100);
