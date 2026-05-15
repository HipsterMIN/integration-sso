-- ============================================================
-- V18: event_type CHECK 제약 갱신 (QIM-OUTBOX-SPEC-001)
-- ============================================================
--
-- 목적:
--   QIM-OUTBOX-SPEC-001에서 신규 정의된 5종 이벤트 타입을 반영하여
--   두 테이블의 CHECK 제약을 갱신한다.
--
-- 영향 테이블:
--   1. ido.provisioning_outbox   — chk_prov_event_type 갱신
--   2. ido.gateway_inbound_audit — chk_gateway_inbound_event_type 갱신
--
-- 데이터 흐름 경로 (변경 이유):
--   QimEventConsumer.isProvisioningTriggerEvent()
--     → triggerProvisioning(qimUserId, eventType, ...)
--       → ProvisioningServiceImpl.insertOutbox(eventType)
--         → provisioning_outbox.event_type  ← CHECK 제약 갱신 필요
--
--   AgencyGatewayServiceImpl (case "AGENCY_USER_REGISTERED","AGENCY_BIZ_CONVERTED")
--     → gateway_inbound_audit.event_type    ← Gateway 이벤트 타입 추가 반영
--
-- 하위 호환성:
--   구 이벤트 타입(USER_REGISTERED, BIZ_CONVERTED, USER_UPDATED, USER_WITHDRAWN)은
--   @Deprecated 코드 제거 전까지 보존 — CHECK 제약에 구/신 동시 허용.
--
-- 관련 코드:
--   ProvisioningEventType.java    (QIM-OUTBOX-SPEC-001 신규 5종 추가)
--   QimEventConsumer.java         (isProvisioningTriggerEvent 4종 문자열 기준)
--   ProvisioningServiceImpl.java  (eventType String → outbox INSERT)
--   AgencyGatewayServiceImpl.java (AGENCY_USER_REGISTERED, AGENCY_BIZ_CONVERTED)
-- ============================================================

-- ────────────────────────────────────────────────────────────
-- 1. ido.provisioning_outbox — chk_prov_event_type 갱신
-- ────────────────────────────────────────────────────────────
-- 기존 제약 삭제
ALTER TABLE ido.provisioning_outbox
    DROP CONSTRAINT IF EXISTS chk_prov_event_type;

-- 신규 제약 추가 (구 타입 + 신규 5종 동시 허용)
ALTER TABLE ido.provisioning_outbox
    ADD CONSTRAINT chk_prov_event_type
        CHECK (event_type IN (
            -- QIM-OUTBOX-SPEC-001 신규 5종 (QimEventConsumer 기준)
            'PERSONAL_MEMBER_REGISTERED',
            'PERSONAL_MEMBER_CONVERTED',
            'BIZ_MEMBER_REGISTERED',
            'BIZ_MEMBER_CONVERTED',
            'MEMBER_WITHDRAWN',
            -- 기존 타입 (하위 호환 — @Deprecated forRemoval=true)
            'USER_REGISTERED',
            'BIZ_CONVERTED',
            'USER_UPDATED',
            'USER_WITHDRAWN'
        ));

-- 컬럼 코멘트 갱신
COMMENT ON COLUMN ido.provisioning_outbox.event_type IS
    '[QIM-OUTBOX-SPEC-001] 프로비저닝 이벤트 타입. '
    '신규 5종: PERSONAL_MEMBER_REGISTERED, PERSONAL_MEMBER_CONVERTED, '
    'BIZ_MEMBER_REGISTERED, BIZ_MEMBER_CONVERTED, MEMBER_WITHDRAWN. '
    '구 3종(@Deprecated): USER_REGISTERED, BIZ_CONVERTED, USER_WITHDRAWN. '
    'V18 이후 신규 레코드는 신규 5종만 사용. '
    '구 타입은 @Deprecated 코드 제거(forRemoval=true) 완료 후 별도 V19 마이그레이션으로 제거 예정.';

-- ────────────────────────────────────────────────────────────
-- 2. ido.gateway_inbound_audit — chk_gateway_inbound_event_type 갱신
-- ────────────────────────────────────────────────────────────
-- 기존 제약 삭제
ALTER TABLE ido.gateway_inbound_audit
    DROP CONSTRAINT IF EXISTS chk_gateway_inbound_event_type;

-- 신규 제약 추가
-- 기관→IdO 방향(Inbound) 이벤트이므로 AGENCY_ 접두사 유지
-- QIM-OUTBOX-SPEC-001 이벤트 타입은 IdO→기관 방향(Outbound) 이므로 이 테이블에는 해당 없음
-- 단, AGENCY_USER_REGISTERED / AGENCY_BIZ_CONVERTED 는 신규 명명 원칙과의 일관성을 위해
-- 대응 타입 추가 (AgencyGatewayServiceImpl case 문과 동기화)
ALTER TABLE ido.gateway_inbound_audit
    ADD CONSTRAINT chk_gateway_inbound_event_type
        CHECK (event_type IN (
            -- 기존 기관→IdO Inbound 이벤트
            'AGENCY_USER_UPDATED',
            'AGENCY_USER_WITHDRAWN',
            'AGENCY_USER_REGISTERED',
            'AGENCY_BIZ_CONVERTED',
            -- QIM-OUTBOX-SPEC-001 기준 추가 (신규 기관 연동 시 사용 가능)
            'AGENCY_PERSONAL_MEMBER_REGISTERED',
            'AGENCY_PERSONAL_MEMBER_CONVERTED',
            'AGENCY_BIZ_MEMBER_REGISTERED',
            'AGENCY_BIZ_MEMBER_CONVERTED',
            'AGENCY_MEMBER_WITHDRAWN',
            'CUSTOM'
        ));

COMMENT ON COLUMN ido.gateway_inbound_audit.event_type IS
    '기관→IdO Inbound 이벤트 타입. '
    'AGENCY_ 접두사 = 기관이 IdO에 통보하는 이벤트. '
    'AGENCY_USER_REGISTERED/BIZ_CONVERTED: 기존 기관 연동 타입(하위 호환). '
    'AGENCY_PERSONAL_MEMBER_*/AGENCY_BIZ_MEMBER_*/AGENCY_MEMBER_WITHDRAWN: QIM-OUTBOX-SPEC-001 신규. '
    'CUSTOM: 기관 자유 형식.';

-- ────────────────────────────────────────────────────────────
-- 3. 검증 쿼리 (실행 후 수동 확인용 주석)
-- ────────────────────────────────────────────────────────────
-- SELECT conname, consrc
-- FROM pg_constraint
-- WHERE conrelid = 'ido.provisioning_outbox'::regclass
--   AND contype = 'c';
--
-- SELECT conname, consrc
-- FROM pg_constraint
-- WHERE conrelid = 'ido.gateway_inbound_audit'::regclass
--   AND contype = 'c';
