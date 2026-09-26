-- 1.0.1 (3차 적대적 점검 H3): relay 가 자기 Flyway 로 idem_hub 에 만들던 ShedLock 테이블을 hub 이력으로 옮긴다.
-- relay 의 Flyway(repair 포함)가 hub 의 flyway_schema_history 를 덮어써 hub 가 기동하지 못하게 만들었다 — relay 는 더 이상 Flyway 를 돌리지 않는다.
-- 이미 relay 가 만든 설치본은 IF NOT EXISTS 로 그대로 지나간다.
CREATE TABLE IF NOT EXISTS idem_hub.shedlock (
    name       VARCHAR(64)                  NOT NULL,
    lock_until TIMESTAMP(3) WITH TIME ZONE  NOT NULL,
    locked_at  TIMESTAMP(3) WITH TIME ZONE  NOT NULL,
    locked_by  VARCHAR(255)                 NOT NULL,
    PRIMARY KEY (name)
);
COMMENT ON TABLE idem_hub.shedlock IS 'ShedLock 분산 락 — idem-relay 잡 (V27 에서 hub 이력으로 이관)';
CREATE INDEX IF NOT EXISTS idx_shedlock_lock_until ON idem_hub.shedlock (lock_until);
