# onepass-support

OnePass 지원 도메인 API 모듈입니다.

## 핵심 정책

- 익명 사용자:
  - Q&A 공개글 작성 가능
  - 비밀글 작성 불가
  - 비밀 답변 대상 불가
- 로그인 사용자(통합인증 완료):
  - Q&A 비밀글 작성 가능
  - 본인 글의 비밀 답변 조회 가능
- 관리자:
  - 공개글/비밀글 모두 답변 가능
  - 공개글에도 비밀 답변 등록 가능
  - 단, 익명 문의에는 비밀 답변 등록 불가

## DB

- PostgreSQL `onepass` DB의 `support` schema 사용
- `faq_group`, `faq`, `qna_post`, `qna_answer` 테이블 기반
- 공공기관 DB 표준화 지침 준용 메타 컬럼:
  - `frst_regist_pnttm`, `frst_register_id`
  - `last_updt_pnttm`, `last_updusr_id`
  - `use_yn`

## API

```text
GET    /api/v1/support/faqs/groups
GET    /api/v1/support/faqs?groupCode=
GET    /api/v1/support/faqs/{id}

GET    /api/v1/support/qna
GET    /api/v1/support/qna/{id}
POST   /api/v1/support/qna

GET    /api/v1/admin/support/qna
POST   /api/v1/admin/support/qna/{id}/answer

GET    /api/v1/admin/support/tickets
GET    /api/v1/admin/support/tickets/{id}
POST   /api/v1/admin/support/phone-consultations
POST   /api/v1/admin/support/tickets/{id}/internal-notes
PATCH  /api/v1/admin/support/tickets/{id}/assignment
PATCH  /api/v1/admin/support/tickets/{id}/status
```

## OpenAPI

- JSON: `/api-docs`
- Swagger UI: `/swagger-ui.html`

공개 Q&A 사용자 헤더:

- `X-User-Id`: 로그인 사용자 식별자
- `X-User-Role`: 사용자 역할 참고값

CS 백오피스 헤더:

- `X-CS-Agent-Id`: CS 상담원 식별자
- `X-CS-Agent-Role`: `CS_AGENT`, `CS_LEAD`, `SUPPORT_ADMIN`, `AUDITOR`

`onepass-support`의 CS 백오피스는 SSO/IM 사용자 로그인과 분리된 운영 영역으로 진행합니다. 현재 Phase 1은 전용 CS 헤더로 경계를 분리했으며, 운영 전환 시 별도 Keycloak client/realm 또는 내부 IAM 연동으로 대체하는 것을 목표로 합니다.
