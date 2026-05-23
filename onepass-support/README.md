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
```

## OpenAPI

- JSON: `/api-docs`
- Swagger UI: `/swagger-ui.html`

권한 헤더(현재):

- `X-User-Id`: 로그인 사용자 식별자
- `X-User-Role`: `ADMIN` 포함 시 관리자 권한
