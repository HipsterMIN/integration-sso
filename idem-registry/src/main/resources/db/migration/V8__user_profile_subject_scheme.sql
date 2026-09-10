-- ============================================================
-- V8: 주체 식별자 스킴 일반화 (범용화 S4, docs/generalization-plan.md §2.3)
-- ============================================================
--
-- ■ 배경
--   user_profile.ci 는 KR 연계정보(CI) 전용 컬럼이다. 범용 코어는 CI 없이 이메일·전화·외부 sub 로도
--   사람을 식별해야 하므로, "무엇으로 식별하는가"(subject_scheme) 와 "그 값"(subject_key) 을 분리한다.
--
-- ■ 변경
--   1. user_profile.subject_scheme  VARCHAR(20) — CI / EMAIL / PHONE / EXTERNAL_SUB (SubjectScheme.isRegistryKey)
--   2. user_profile.subject_key     VARCHAR(512) — 암호화 저장 (ci 와 같은 v{n}.{iv}.{ct} 형식)
--   3. 백필: ci 가 있는 행은 subject_scheme='CI', subject_key=ci
--
-- ■ 호환
--   - ci 컬럼은 유지한다(읽기 호환). CI 스킴 등록은 ci 와 subject_key 에 같은 값을 쓴다.
--   - 조회는 여전히 auth_mean_mapping.identifier_hash 로만 한다. subject_key 는 무작위 IV 암호문이라 조회 키가 아니다.
--   - EMAIL/PHONE 의 identifier_hash 는 "EMAIL:"·"PHONE:" 접두 후 SHA-256 (SubjectScheme.identifierHash).
--     CI/EXTERNAL_SUB 는 기존과 같이 원문 SHA-256.
-- ============================================================

ALTER TABLE user_profile
    ADD COLUMN IF NOT EXISTS subject_scheme VARCHAR(20)  NULL COMMENT '주체 식별자 스킴: CI / EMAIL / PHONE / EXTERNAL_SUB',
    ADD COLUMN IF NOT EXISTS subject_key    VARCHAR(512) NULL COMMENT '주체 키 — 암호화 저장 v{n}.{iv}.{ct}';

UPDATE user_profile
   SET subject_scheme = 'CI',
       subject_key    = ci
 WHERE ci IS NOT NULL
   AND subject_scheme IS NULL;

CREATE INDEX IF NOT EXISTS idx_user_profile_subject_scheme ON user_profile (subject_scheme);
