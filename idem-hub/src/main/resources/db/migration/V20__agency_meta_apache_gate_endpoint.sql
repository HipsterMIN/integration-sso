-- S1 범용화 (docs/generalization-plan.md): agency_meta.bridge_endpoint 컬럼의 이중 의미 해소
--
-- 지금까지 APACHE_GATE 연동 유형은 전용 컬럼이 없어 bridge_endpoint 를 "게이트웨이 세션 등록 URL" 로 재사용했다
-- (AgencyMetaRepositoryImpl 이 integration_type 에 따라 컬럼 뜻을 바꿔 읽음). 전용 컬럼을 두고 기존 값을 옮긴다.
--
-- integration_type 허용값 집합은 V1 의 chk_integration_type 과 Java IntegrationType 열거형이 같아야 한다.

ALTER TABLE ido.agency_meta
    ADD COLUMN IF NOT EXISTS apache_gate_endpoint VARCHAR(500);

COMMENT ON COLUMN ido.agency_meta.apache_gate_endpoint
    IS 'APACHE_GATE 연동 유형 전용 — 게이트웨이(idem-agent) 세션 헤더 사전 등록 URL. S1 에서 bridge_endpoint 와 분리';
COMMENT ON COLUMN ido.agency_meta.bridge_endpoint
    IS 'BRIDGE 연동 유형 전용 — Bridge 서버 Payload 푸시 엔드포인트';

-- 기존 APACHE_GATE 기관: bridge_endpoint 에 들어 있던 값을 전용 컬럼으로 이동
UPDATE ido.agency_meta
   SET apache_gate_endpoint = bridge_endpoint,
       bridge_endpoint      = NULL
 WHERE integration_type = 'APACHE_GATE'
   AND apache_gate_endpoint IS NULL
   AND bridge_endpoint IS NOT NULL;
