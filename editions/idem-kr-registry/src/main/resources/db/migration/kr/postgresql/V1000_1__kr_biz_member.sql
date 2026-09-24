-- ═══════════════════════════════════════════════════════════════════════════
-- Idem KR Public Edition — registry 확장 스키마 (S8-a)
--
-- 버전 1000.x 는 KR 에디션 전용 대역이다. 코어(idem-registry, V1~V999) 뒤에 항상 적용되고
-- 코어가 새 마이그레이션을 더해도 순서가 꼬이지 않는다(outOfOrder 불필요).
-- location 은 KrRegistryEditionConfig(FlywayConfigurationCustomizer) 가 더한다.
-- ═══════════════════════════════════════════════════════════════════════════

-- 기업회원 전환 정보 — 사업자등록번호 기반 (SMES 회원 유형 A102)
CREATE TABLE biz_member (
    qim_user_id     VARCHAR(36)     NOT NULL,
    biz_reg_no      VARCHAR(20)     NOT NULL,
    company_name    VARCHAR(200)    NOT NULL,
    rep_name_masked VARCHAR(100),
    biz_type        VARCHAR(50),
    biz_status      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    verified_at     TIMESTAMPTZ(6),
    converted_at    TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_biz_member          PRIMARY KEY (qim_user_id),
    CONSTRAINT uq_biz_reg_no          UNIQUE (biz_reg_no),
    CONSTRAINT fk_biz_member_qim_user FOREIGN KEY (qim_user_id) REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);
COMMENT ON TABLE biz_member IS 'KR 에디션 — 기업회원 전환 정보 (사업자등록번호 기반)';
