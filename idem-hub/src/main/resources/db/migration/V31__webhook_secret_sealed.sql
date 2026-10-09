-- ═══════════════════════════════════════════════════════════════════════════
-- 1.1.1 G1-4 (플랜 §2.4): 웹훅 서명 비밀을 KMS 로 봉인해 저장한다.
--   종전 signing_secret_hash 는 이름과 달리 원문을 담고 있었다(발송 때 원문이 필요해서).
--   signing_secret_sealed : KmsClient.encrypt(원문) — local:v1:… / vault / NHN envelope (idem.hub.kms.provider)
--   signing_secret_hash   : 이제 이름대로 SHA-256(원문) — 지문(앞 8자) 표시·대조용. 원문은 더 이상 여기에 두지 않는다
--   secret_rotated_at     : 마지막 회전(POST /api/v1/admin/agencies/{code}/webhook/rotate-secret)
-- 기존 행의 원문은 첫 기동에서 WebhookSigningSecrets 가 봉인하고 해시로 바꾼다(signing_secret_sealed IS NULL 인 행).
-- 엔드포인트만 등록된 기관(비밀 없음)도 행을 가질 수 있게 NOT NULL 을 푼다 — 종전에는 관리 API 의 INSERT 가 이 제약에 걸려 조용히 실패했다.
-- ═══════════════════════════════════════════════════════════════════════════
ALTER TABLE idem_hub.agency_webhook_config
    ADD COLUMN IF NOT EXISTS signing_secret_sealed VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS secret_rotated_at     TIMESTAMPTZ;
ALTER TABLE idem_hub.agency_webhook_config ALTER COLUMN signing_secret_hash DROP NOT NULL;

COMMENT ON COLUMN idem_hub.agency_webhook_config.signing_secret_hash   IS 'SHA-256(서명 비밀) — 지문·대조용. 원문은 signing_secret_sealed(KMS 봉인)에만 (1.1.1 G1-4; 그 전에는 원문이 여기 있었다)';
COMMENT ON COLUMN idem_hub.agency_webhook_config.signing_secret_sealed IS 'KMS 봉인된 웹훅 서명 비밀(KmsClient.encrypt) — 발송 때 복호화한다';
COMMENT ON COLUMN idem_hub.agency_webhook_config.secret_rotated_at     IS '마지막 회전 시각 (관리 API rotate-secret)';
