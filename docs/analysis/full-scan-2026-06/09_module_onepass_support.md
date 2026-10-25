# 09 · Module Deep Dive — onepass-support (CS 백오피스 + Q&A/FAQ)

> 원천: `/home/user/webapp/onepass-support/` · 정본 `docs/onepass-support-cs-backoffice-plan.md` + `onepass-support-board-redesign-plan.md`.
> 파일 수 51 · Postgres `support` 스키마 · V1~V3.

---

## 1. 삼각 요약

| 항목 | 값 |
|---|---|
| 포트 | 8085 |
| DB | PostgreSQL 16 (schema: `support`) |
| 마이그레이션 | V1~V3 (최신 `V3__create_cs_backoffice_schema.sql`) |
| 파일 수 | 51 |
| 도입 시점 | PR #186 (`8f22660`, 2026-05) |
| 후속 | PR #197 (인증 모델 보완, Keycloak JWT + 익명 공존) |

---

## 2. 서브패키지

```
onepass-support/src/main/java/kr/go/smes/support/
├── api/
│   └── dto/          — 요청/응답 DTO
├── application/      — Use case
├── config/           — Bean 설정
└── domain/           — 엔티티
```

---

## 3. 마이그레이션 V1~V3

| V | 파일 | 요지 |
|---|---|---|
| V1 | `create_support_schema.sql` | Q&A + FAQ 기본 |
| V2 | `add_support_schema_comments.sql` | 컬럼 코멘트 |
| V3 | `create_cs_backoffice_schema.sql` | **CS 티켓 백오피스** (PR #190 `7fe71a3`) |

---

## 4. 인증 모델 (PR #197 `f16b06f`)

**정책**: Keycloak JWT 검증 + **익명 공존**.
- 로그인 사용자: Keycloak JWT → sub 클레임으로 작성자 확정.
- 비로그인 사용자(익명): 익명 게시 허용 (특정 게시판 정책 하), `author_alias` 필드 사용.
- **핵심**: Q-IM 은 이 인증 모델에 관여하지 않음 (Q-IM SoR 경계 준수).

---

## 5. CS 티켓 (V3)

**PR #190 `7fe71a3`** — Testcontainers + 인가 분기 + 티켓 멱등성 통합 테스트 추가.

주요 컬럼(예상):
```
support_ticket
  ticket_id       BIGSERIAL PK
  external_key    VARCHAR(128) UNIQUE   -- 멱등성
  requester_id    VARCHAR(128)
  agent_id        VARCHAR(128)
  status          VARCHAR(32)           -- OPEN / IN_PROGRESS / CLOSED
  title, body     TEXT
  created_at, updated_at
```

멱등성 키: `external_key` — 동일 요청 재전송 시 UNIQUE 충돌로 새 티켓 생성 방지.

---

## 6. 게시판 재설계 (`onepass-support-board-redesign-plan.md`)

**PR #198** 로 계획서 작성 (`5364b1f`, `f16b06f`). 구현은 대기(관찰).

주요 재설계 축:
- FAQ 유형 확장 (카테고리별)
- Q&A 답변 워크플로 (상담원 배정, 상태 전이)
- 첨부 파일 처리
- 익명 vs 로그인 답변 정책

---

## 7. Q-IM 헌장과의 관계

| 항목 | Q-IM (03c 헌장) | onepass-support |
|---|---|---|
| 저장 대상 | 회원 항구적 사실 | Q&A/FAQ/CS 티켓 |
| SoR 경계 | 회원 | 문의·상담 |
| 관리자 UI | **금지** | **허용** (CS 백오피스) |
| 인증 | (직접 없음) | Keycloak JWT + 익명 |

**§6.5 헌장**에서 "Q-IM 관리자 UI 금지" 를 명시한 결정적 이유 중 하나는 **onepass-support 로 관리자 기능을 이관하기 위함**이다 (관찰).

---

## 8. 관찰된 리스크

| # | 항목 | 위험 | 근거 |
|---|---|---|---|
| L1 | 게시판 재설계 구현 대기 | 🟡 MED | PR #198 계획서 |
| L2 | 익명 스팸 대응 (rate limit) | 🟡 MED | 인증 모델 |
| L3 | 첨부 파일 스토리지 (S3?) 정책 미상 | 🟡 MED | V3 스키마 |
| L4 | q-authz 편입 후 상담원 권한 관리 이관 여부 | 🟡 MED | 향후 |

---

## 9. 참조
- 소스: `/home/user/webapp/onepass-support/`
- 계획: `docs/onepass-support-cs-backoffice-plan.md`, `docs/onepass-support-board-redesign-plan.md`
- 관련 PR: #186 (초기), #190 (테스트), #192 (Yn), #197 (인증), #198 (재설계 계획)
