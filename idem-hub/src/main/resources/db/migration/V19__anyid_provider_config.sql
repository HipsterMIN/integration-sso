-- =============================================================================
-- IdO V19: Any-ID 설치형 인증수단 provider_config 등록
-- =============================================================================
-- 목적:
--   1. provider_config.broker_mode CHECK 제약에 'anyid' 값 추가
--      (기존: keycloak/qsign/direct → 변경: keycloak/qsign/direct/anyid)
--      BrokerService.isAnyIdProvider() 가 broker_mode='anyid' 로 판별하므로 필수.
--
--   2. Any-ID 설치형 인증수단 5개 레코드 삽입:
--      MOBILE_ID / EASY_SIGN / JOINT_CERT / FINANCIAL_CERT / PRIVATE_ID
--      (FINANCIAL_CERT / JOINT_CERT 는 V3에서 broker_mode='direct'로 삽입됨 →
--       broker_mode를 'anyid'로 교체하고 provider_type을 'NON_STANDARD'로 설정)
--
--   3. 기존 FINANCIAL_CERT / JOINT_CERT 레코드 broker_mode 수정
--      (V3: broker_mode='direct' → V19: broker_mode='anyid')
--
--   4. V10에서 자동 생성된 provider_circuit_config 레코드 보완
--      (Any-ID 신규 수단에 대해 기본 회로차단기 설정 삽입)
--
-- 연관 클래스:
--   - io.github.hipstermin.idem.hub.broker.BrokerService#isAnyIdProvider()
--   - io.github.hipstermin.idem.hub.broker.anyid.AnyIdBrokerAdapter
--   - io.github.hipstermin.idem.hub.broker.provider.ProviderConfig.ProviderType
--
-- 참조 문서:
--   - wiki/iam/08-kr-auth-providers-guide.md §8 (provider_config DB 등록)
--   - wiki/iam/06-install-type-integration.md
--
-- 기존 호환성:
--   - ON CONFLICT (provider_code) DO UPDATE 사용 — 재실행 안전
--   - ADD CONSTRAINT IF NOT EXISTS 패턴 사용 (DROP → ADD)
--   - 운영 중 provider_type='NON_STANDARD' 데이터는 변경 없음
--
-- 작성일: 2026-05-19
-- PR: #138 (shipster → main)
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. provider_config.broker_mode CHECK 제약 확장
--    기존: CHECK (broker_mode IN ('keycloak','qsign','direct'))
--    변경: CHECK (broker_mode IN ('keycloak','qsign','direct','anyid'))
--
--    PostgreSQL은 CHECK 제약을 직접 ALTER할 수 없으므로 DROP → ADD 방식 사용.
--    제약명은 V3에서 'chk_ido_provider_mode'로 정의됨.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE idem_hub.provider_config
    DROP CONSTRAINT IF EXISTS chk_ido_provider_mode;

ALTER TABLE idem_hub.provider_config
    ADD CONSTRAINT chk_ido_provider_mode
        CHECK (broker_mode IN ('keycloak', 'qsign', 'direct', 'anyid'));

COMMENT ON CONSTRAINT chk_ido_provider_mode ON idem_hub.provider_config
    IS 'V19 확장: anyid 추가 (Any-ID 설치형 경로) — BrokerService.isAnyIdProvider() 참조';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 기존 FINANCIAL_CERT / JOINT_CERT broker_mode 수정
--    V3에서 broker_mode='direct' 로 삽입되었으나,
--    Any-ID 설치형 경로로 연동하므로 'anyid' 로 변경.
--    provider_type 은 V6 마이그레이션에서 이미 'NON_STANDARD' 로 설정됨.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE idem_hub.provider_config
SET
    broker_mode  = 'anyid',
    display_name = '공동인증서 (Any-ID)',
    updated_at   = NOW()
WHERE provider_code = 'JOINT_CERT'
  AND broker_mode   = 'direct';

UPDATE idem_hub.provider_config
SET
    broker_mode  = 'anyid',
    display_name = '금융인증서 (Any-ID)',
    updated_at   = NOW()
WHERE provider_code = 'FINANCIAL_CERT'
  AND broker_mode   = 'direct';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Any-ID 신규 인증수단 삽입
--    행안부 Any-ID 설치형 연동 인증수단 (AnyIdBrokerAdapter 경유):
--      - MOBILE_ID   : 모바일 신분증 (행안부 DID 기반, L2)
--      - EASY_SIGN   : 간편인증 (민간인증서 브로커링 11종, L1)
--      - PRIVATE_ID  : 민간ID/소셜 (PASS·카카오·네이버 등 간접, L1)
--
--    ON CONFLICT DO UPDATE: broker_mode / provider_type / display_name 최신값 보장.
--    idp_hint 는 NULL — Any-ID 는 Keycloak relay 미사용.
--
--    auth_level 기준 (행안부 전자서명 인증 등급 고시):
--      L1 = 기억기반 (ID/PW, OTP)
--      L2 = 소유기반 (휴대폰, DID, 생체)
--      L3 = PKI 전자서명 (공동인증서, 금융인증서, GPKI)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO idem_hub.provider_config
    (provider_code, display_name, auth_level, broker_mode, provider_type, idp_hint, active)
VALUES
    ('MOBILE_ID',  '모바일 신분증',        'L2', 'anyid', 'NON_STANDARD', NULL, TRUE),
    ('EASY_SIGN',  '간편인증 (Any-ID)',    'L1', 'anyid', 'NON_STANDARD', NULL, TRUE),
    ('PRIVATE_ID', '민간ID (소셜 간접)',   'L1', 'anyid', 'NON_STANDARD', NULL, TRUE)
ON CONFLICT (provider_code) DO UPDATE
    SET broker_mode   = EXCLUDED.broker_mode,
        provider_type = EXCLUDED.provider_type,
        display_name  = EXCLUDED.display_name,
        updated_at    = NOW();

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. provider_circuit_config 보완
--    V10에서 신규 수단은 자동 삽입되지 않았을 수 있음 (V10 실행 시 해당 레코드 없음).
--    Any-ID 수단은 외부 HTTP 의존성이 있으므로 회로차단기 임계값을 보수적으로 설정:
--      - failure_rate_threshold: 50% (기본 60%보다 낮게 — Any-ID 서버 불안정 대비)
--      - wait_duration_in_open_ms: 60000 (60초 — 기본 30초보다 길게)
--      - sliding_window_size: 10
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO idem_hub.provider_circuit_config
    (provider_code, sliding_window_size, failure_rate_threshold,
     slow_call_rate_threshold, slow_call_duration_threshold_ms,
     wait_duration_in_open_ms, permitted_calls_in_half_open,
     minimum_number_of_calls, enabled, note)
VALUES
    ('MOBILE_ID',  10, 50, 80, 5000, 60000, 3, 5, TRUE,
        'Any-ID 설치형 — 모바일 신분증 (외부 DID 검증 레이턴시 고려)'),
    ('EASY_SIGN',  10, 50, 80, 5000, 60000, 3, 5, TRUE,
        'Any-ID 설치형 — 간편인증 (민간인증서 11종 브로커링)'),
    ('JOINT_CERT', 10, 50, 80, 5000, 60000, 3, 5, TRUE,
        'Any-ID 설치형 — 공동인증서 (NPKI 검증 레이턴시 고려)'),
    ('FINANCIAL_CERT', 10, 50, 80, 5000, 60000, 3, 5, TRUE,
        'Any-ID 설치형 — 금융인증서 (KFTC 클라우드 의존)'),
    ('PRIVATE_ID', 10, 50, 80, 3000, 30000, 5, 5, TRUE,
        'Any-ID 설치형 — 민간ID 소셜 간접 (빠른 응답 예상)')
ON CONFLICT (provider_code) DO UPDATE
    SET failure_rate_threshold       = EXCLUDED.failure_rate_threshold,
        wait_duration_in_open_ms     = EXCLUDED.wait_duration_in_open_ms,
        slow_call_duration_threshold_ms = EXCLUDED.slow_call_duration_threshold_ms,
        note                         = EXCLUDED.note,
        updated_at                   = NOW();

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. 결과 검증 (정보성 주석 — 실제 실행 후 psql로 확인)
-- ─────────────────────────────────────────────────────────────────────────────
-- SELECT provider_code, display_name, auth_level, broker_mode, provider_type, active
-- FROM idem_hub.provider_config
-- WHERE broker_mode = 'anyid'
-- ORDER BY auth_level DESC, provider_code;
--
-- 예상 결과 (5행):
--   FINANCIAL_CERT | 금융인증서 (Any-ID)      | L3 | anyid | NON_STANDARD | t
--   JOINT_CERT     | 공동인증서 (Any-ID)      | L3 | anyid | NON_STANDARD | t
--   MOBILE_ID      | 모바일 신분증             | L2 | anyid | NON_STANDARD | t
--   EASY_SIGN      | 간편인증 (Any-ID)        | L1 | anyid | NON_STANDARD | t
--   PRIVATE_ID     | 민간ID (소셜 간접)        | L1 | anyid | NON_STANDARD | t
