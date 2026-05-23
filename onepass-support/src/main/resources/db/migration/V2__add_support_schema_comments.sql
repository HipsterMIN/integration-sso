-- ============================================================
-- onepass-support schema comment
-- ============================================================

COMMENT ON TABLE faq_group IS 'FAQ 분류(그룹) 마스터. FAQ를 기능/업무 영역별로 묶어 사용자 검색성과 운영 관리성을 높인다.';
COMMENT ON COLUMN faq_group.faq_group_id IS 'FAQ 그룹 식별자(UUID).';
COMMENT ON COLUMN faq_group.tenant_id IS '테넌트 식별자. 멀티테넌트 운영 시 분리 키로 사용한다.';
COMMENT ON COLUMN faq_group.faq_group_cd IS 'FAQ 그룹 코드(업무 코드). 예: LOGIN, ACCOUNT, CERTIFICATE.';
COMMENT ON COLUMN faq_group.faq_group_nm IS 'FAQ 그룹명(사용자 노출 명칭).';
COMMENT ON COLUMN faq_group.faq_group_desc IS 'FAQ 그룹 설명(운영자/관리자 참고용).';
COMMENT ON COLUMN faq_group.sort_sn IS '정렬 순번. 숫자가 작을수록 먼저 노출된다.';
COMMENT ON COLUMN faq_group.use_yn IS '사용 여부(Y/N). N이면 목록/검색에서 제외한다.';
COMMENT ON COLUMN faq_group.frst_regist_pnttm IS '최초 등록 일시(UTC).';
COMMENT ON COLUMN faq_group.frst_register_id IS '최초 등록자 ID(관리자/배치 계정).';
COMMENT ON COLUMN faq_group.last_updt_pnttm IS '최종 수정 일시(UTC).';
COMMENT ON COLUMN faq_group.last_updusr_id IS '최종 수정자 ID(관리자/배치 계정).';

COMMENT ON TABLE faq IS 'FAQ 본문. 그룹별 질문/답변을 저장하며 공개 여부 및 정렬 순번을 관리한다.';
COMMENT ON COLUMN faq.faq_id IS 'FAQ 식별자(UUID).';
COMMENT ON COLUMN faq.faq_group_id IS 'FAQ 그룹 식별자. faq_group.faq_group_id 참조.';
COMMENT ON COLUMN faq.tenant_id IS '테넌트 식별자. 멀티테넌트 운영 시 분리 키로 사용한다.';
COMMENT ON COLUMN faq.agency_id IS '기관 코드. 기관별 맞춤 FAQ가 필요한 경우 필터 조건으로 사용한다.';
COMMENT ON COLUMN faq.faq_qstn_cn IS 'FAQ 질문 내용.';
COMMENT ON COLUMN faq.faq_ans_cn IS 'FAQ 답변 내용(마크다운/텍스트).';
COMMENT ON COLUMN faq.expsr_yn IS '대외 노출 여부(Y/N). N이면 공개 API에서 제외한다.';
COMMENT ON COLUMN faq.sort_sn IS '정렬 순번. 그룹 내 노출 순서 제어에 사용한다.';
COMMENT ON COLUMN faq.use_yn IS '사용 여부(Y/N). N이면 논리 비활성 상태이다.';
COMMENT ON COLUMN faq.frst_regist_pnttm IS '최초 등록 일시(UTC).';
COMMENT ON COLUMN faq.frst_register_id IS '최초 등록자 ID(관리자/배치 계정).';
COMMENT ON COLUMN faq.last_updt_pnttm IS '최종 수정 일시(UTC).';
COMMENT ON COLUMN faq.last_updusr_id IS '최종 수정자 ID(관리자/배치 계정).';

COMMENT ON TABLE qna_post IS 'Q&A 질문 게시글. 익명/회원/비밀글 여부 및 상태(OPEN/ANSWERED/CLOSED)를 관리한다.';
COMMENT ON COLUMN qna_post.qna_id IS 'Q&A 게시글 식별자(UUID).';
COMMENT ON COLUMN qna_post.tenant_id IS '테넌트 식별자. 멀티테넌트 운영 시 분리 키로 사용한다.';
COMMENT ON COLUMN qna_post.agency_id IS '기관 코드. 기관 단위 Q&A 조회/통계에 사용한다.';
COMMENT ON COLUMN qna_post.qna_ttl IS 'Q&A 제목.';
COMMENT ON COLUMN qna_post.qna_cn IS 'Q&A 질문 본문.';
COMMENT ON COLUMN qna_post.qna_stts_cd IS 'Q&A 상태 코드. OPEN(접수)/ANSWERED(답변완료)/CLOSED(종결).';
COMMENT ON COLUMN qna_post.secret_yn IS '비밀글 여부(Y/N). Y이면 작성자와 관리자만 본문/비밀답변 조회 가능.';
COMMENT ON COLUMN qna_post.anonymous_yn IS '익명 작성 여부(Y/N). Y이면 writer_user_id 없이 익명 정보로 관리한다.';
COMMENT ON COLUMN qna_post.writer_user_id IS '작성자 사용자 ID(통합인증 완료 사용자). 익명 작성 시 NULL.';
COMMENT ON COLUMN qna_post.anonymous_display_name IS '익명 작성 표시 이름. 익명 작성 시 필수로 관리한다.';
COMMENT ON COLUMN qna_post.anonymous_contact_email IS '익명 작성 연락 이메일. 추후 운영 답변 안내/문의 확인용.';
COMMENT ON COLUMN qna_post.use_yn IS '사용 여부(Y/N). N이면 논리 삭제/비활성으로 처리한다.';
COMMENT ON COLUMN qna_post.frst_regist_pnttm IS '최초 등록 일시(UTC).';
COMMENT ON COLUMN qna_post.frst_register_id IS '최초 등록자 ID. 익명 작성은 ANONYMOUS 같은 시스템 값 사용.';
COMMENT ON COLUMN qna_post.last_updt_pnttm IS '최종 수정 일시(UTC).';
COMMENT ON COLUMN qna_post.last_updusr_id IS '최종 수정자 ID(작성자/관리자/배치 계정).';

COMMENT ON TABLE qna_answer IS 'Q&A 답변. 공개답변/비밀답변 여부를 분리하여 저장한다.';
COMMENT ON COLUMN qna_answer.qna_answer_id IS 'Q&A 답변 식별자(UUID).';
COMMENT ON COLUMN qna_answer.qna_id IS '원본 Q&A 게시글 식별자. qna_post.qna_id 참조.';
COMMENT ON COLUMN qna_answer.answer_cn IS '답변 본문.';
COMMENT ON COLUMN qna_answer.secret_yn IS '비밀답변 여부(Y/N). Y이면 작성자/관리자만 조회 가능.';
COMMENT ON COLUMN qna_answer.answered_by_user_id IS '답변 관리자 사용자 ID.';
COMMENT ON COLUMN qna_answer.use_yn IS '사용 여부(Y/N). N이면 논리 삭제/비활성으로 처리한다.';
COMMENT ON COLUMN qna_answer.frst_regist_pnttm IS '최초 등록 일시(UTC).';
COMMENT ON COLUMN qna_answer.frst_register_id IS '최초 등록자 ID(관리자 계정).';
COMMENT ON COLUMN qna_answer.last_updt_pnttm IS '최종 수정 일시(UTC).';
COMMENT ON COLUMN qna_answer.last_updusr_id IS '최종 수정자 ID(관리자 계정).';

COMMENT ON TABLE support_audit_log IS '지원 도메인 감사 로그. 문의/답변/노출정책 변경 등 주요 행위를 추적한다.';
COMMENT ON COLUMN support_audit_log.audit_id IS '감사 로그 식별자(UUID).';
COMMENT ON COLUMN support_audit_log.tenant_id IS '테넌트 식별자.';
COMMENT ON COLUMN support_audit_log.actor_user_id IS '행위자 사용자 ID(회원/관리자/시스템).';
COMMENT ON COLUMN support_audit_log.action_cd IS '행위 코드. 예: QNA_CREATE, QNA_ANSWER_CREATE, FAQ_UPDATE.';
COMMENT ON COLUMN support_audit_log.target_type_cd IS '대상 유형 코드. 예: QNA_POST, QNA_ANSWER, FAQ.';
COMMENT ON COLUMN support_audit_log.target_id IS '행위 대상 리소스 식별자(UUID).';
COMMENT ON COLUMN support_audit_log.ip_addr IS '요청자 IP 주소.';
COMMENT ON COLUMN support_audit_log.user_agent_cn IS '요청 User-Agent 문자열.';
COMMENT ON COLUMN support_audit_log.frst_regist_pnttm IS '감사 로그 기록 시각(UTC).';
COMMENT ON COLUMN support_audit_log.frst_register_id IS '감사 로그 생성 주체 ID(시스템 계정 포함).';

COMMENT ON INDEX idx_faq_group_tenant IS 'FAQ 그룹 조회(tenant/use_yn/정렬) 성능을 위한 인덱스.';
COMMENT ON INDEX idx_faq_tenant_agency IS 'FAQ 목록 조회(tenant/agency/use_yn/정렬) 성능을 위한 인덱스.';
COMMENT ON INDEX idx_qna_post_tenant_agency IS 'Q&A 목록 조회(tenant/agency/상태/등록일 역순) 성능을 위한 인덱스.';
COMMENT ON INDEX idx_qna_post_writer IS '작성자 본인 문의 조회(마이페이지) 성능을 위한 인덱스.';
COMMENT ON INDEX idx_qna_answer_qna_id IS '게시글별 답변 조회(등록일 순) 성능을 위한 인덱스.';
COMMENT ON INDEX idx_support_audit_target IS '감사 로그 대상별 추적 조회 성능을 위한 인덱스.';

