-- ============================================================================
-- V19: ShedLock 분산 락 테이블 추가
-- ============================================================================
--
-- 목적: outbox-relay-batch 서비스가 다중 Pod 환경에서 Outbox 릴레이 Job을
--       단 하나의 인스턴스만 실행하도록 보장하기 위한 JDBC LockProvider 테이블.
--
-- ShedLock JDBC Provider 문서:
--   https://github.com/lukas-krecan/ShedLock#jdbctemplate
--
-- 컬럼 설명:
--   name       - Job 이름 (e.g. "ido-kafka-relay", "qim-kafka-relay")
--   lock_until - 락 만료 시각 (lockAtMostFor 기준 — 비정상 종료 시 자동 해제)
--   locked_at  - 락 획득 시각 (모니터링/감사용)
--   locked_by  - 락을 획득한 서버 식별자 (Pod 호스트명:PID)
--
-- 인덱스:
--   PK(name): 락 이름 단위 원자적 UPDATE 보장
--   idx_shedlock_lock_until: 만료된 락 조회 최적화 (잠금 정리 쿼리용)
--
-- ⚠️ 운영 주의사항:
--   이 테이블은 ido 서비스와 outbox-relay-batch 서비스가 공유.
--   ido 서비스의 V18까지 마이그레이션이 완료된 상태에서
--   outbox-relay-batch 최초 기동 시 V19가 적용됨.
--   ido 서비스 V19 스크립트와 번호 충돌 방지: 이후 ido V19부터는 V20+로 관리.
-- ============================================================================

CREATE TABLE IF NOT EXISTS idem_hub.shedlock (
    name       VARCHAR(64)                  NOT NULL,
    lock_until TIMESTAMP(3) WITH TIME ZONE  NOT NULL,
    locked_at  TIMESTAMP(3) WITH TIME ZONE  NOT NULL,
    locked_by  VARCHAR(255)                 NOT NULL,
    PRIMARY KEY (name)
);

COMMENT ON TABLE  idem_hub.shedlock           IS 'ShedLock 분산 락 메타 테이블 — outbox-relay-batch 전용';
COMMENT ON COLUMN idem_hub.shedlock.name       IS 'Job 이름 (e.g. ido-kafka-relay)';
COMMENT ON COLUMN idem_hub.shedlock.lock_until IS '락 만료 시각 (lockAtMostFor 기준)';
COMMENT ON COLUMN idem_hub.shedlock.locked_at  IS '락 획득 시각';
COMMENT ON COLUMN idem_hub.shedlock.locked_by  IS '락 보유 인스턴스 식별자 (Pod 호스트명)';

-- 만료 락 정리 쿼리 최적화 인덱스
CREATE INDEX IF NOT EXISTS idx_shedlock_lock_until
    ON idem_hub.shedlock (lock_until);

-- ============================================================================
-- 초기 락 레코드 시드 (선택적 — Job 이름 목록 가시성 확보)
-- ShedLock은 첫 실행 시 INSERT하므로 사전 시드 불필요.
-- 운영팀 모니터링 용이성을 위해 빈 레코드 삽입.
-- ============================================================================
-- INSERT INTO idem_hub.shedlock (name, lock_until, locked_at, locked_by) VALUES
--     ('ido-kafka-relay',         NOW() - INTERVAL '1 hour', NOW(), 'init'),
--     ('ido-qim-kafka-relay',     NOW() - INTERVAL '1 hour', NOW(), 'init'),
--     ('qim-kafka-relay',         NOW() - INTERVAL '1 hour', NOW(), 'init'),
--     ('qim-kafka-relay-failed',  NOW() - INTERVAL '1 hour', NOW(), 'init'),
--     ('qsign-kafka-relay',       NOW() - INTERVAL '1 hour', NOW(), 'init'),
--     ('ido-provisioning-relay',  NOW() - INTERVAL '1 hour', NOW(), 'init'),
--     ('ido-webhook-relay',       NOW() - INTERVAL '1 hour', NOW(), 'init')
-- ON CONFLICT (name) DO NOTHING;
