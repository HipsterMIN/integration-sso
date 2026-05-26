# OnePass Support 게시판(고객센터) 재설계 보완 플랜

> 작성일: 2026-05-26
> 기준 기획안: `05.게시판.zip` (문의하기 목록/글쓰기/상세(타인글)/상세(본인글)/상세(관리자)/자주묻는질문 6장)
> 적용 범위: `onepass-support` 모듈(백엔드) + `onepass-fe`(프론트엔드 신규 영역)

---

## 0. 핵심 전제 (사용자 지시)

1. **`onepass-support`는 SSO/IM과 완전히 분리된 독립 서비스/프로세스**다.
2. **사용자가 고객센터를 이용할 때 로그인 여부에 의존하지 않는다.**
   - 즉 기존 README에 있던 "로그인 사용자는 비밀글 작성 가능, 비로그인은 공개글만" 정책은 **폐기**한다.
   - 대신 기획안 그대로 **누구나 작성 → 비밀번호 기반 본인 확인 → 본인글 수정/조회**로 모델을 단일화한다.
3. CS 백오피스(관리자 답변) 영역은 별도 게이트로 유지하되 본 문서의 변경에 맞춰 답변/상태 변경 UX를 정렬한다.
4. 기획 이미지의 화면명("게시판")에 얽매이지 말고 **전체 구성 의도**(카테고리 컬럼, 공개/비공개, 비밀번호, 익명 표시, 단순 답변 1건)에 맞춘다.

---

## 1. 기획안 분석 결과 (이미지 6장 종합)

### 1.1 공통 레이아웃

- PC 전용 와이드, 좌측 카드형 사이드바 + 우측 본문
- 좌측 메뉴: `고객 센터` 카드 아래 `문의하기`, `자주묻는 질문` 2개
- 상단 헤더: 로고(`중기 통합회원`) / 사용자 인사말 / `로그아웃`·`고객문의`·`홈페이지돌아가기` 버튼
- 본문 상단 파란 배너: 브레드크럼(`홈 > 고객센터 > 문의하기`) + 페이지 제목
- 상단 안내 박스: "궁금한게 있으신가요? 먼저 자주 묻는 질문을 한번 살펴보세요." / "직접 상담원과 통화도 해보세요! (전화문의 : 000-000-0000)"
- 푸터: `개인정보처리방침`/`이용약관`/`중소기업통합플랫폼`/시스템 장애 문의/메일/주소/대표전화/copyright

> 👉 사용자 인사말("유상옥님 안녕하세요")이 있어 헤더에 로그인 상태 표시가 있지만, **본문 게시판 동작 자체는 비로그인 동작 가능**한 구조다. 헤더는 공통 GNB 위젯, 본문은 익명 가능 흐름으로 분리한다.

### 1.2 문의하기 목록 (이미지 1)

| 컬럼 | 비고 |
|---|---|
| 순번 | 게시글 번호(시퀀스 또는 DESC 인덱싱) |
| 구분 | 카테고리/채널 — `벤처24`, `기타`, `소상365`, `소상24` 등 |
| 제목 | 클릭 시 상세 진입. 비공개 글은 별도 처리(자물쇠/마스킹은 기획에서 미노출이지만 정책 필요) |
| 상태 | `작성중`, `답변완료` (2값 확인) |
| 작성일 | YYYY-MM-DD |
| 작성자 | 한글 이름 + 영문ID 혼재 — 익명/회원 모두 표시명 노출 |

- 검색 영역: `카테고리` 드롭다운 / `제목` 드롭다운(검색 필드 선택?) / `작성자` 드롭다운 + 검색어 입력창
- 상단: `검색결과 17건` / `전체` 필터 / `목록 표시 개수 10개`
- 페이지네이션: `< 이전 1 2 3 4 5 6 7 8 ... 99 다음 >`
- 우측 하단: `문의하기`(글쓰기) 큰 버튼

### 1.3 문의하기 글쓰기 (이미지 2)

| # | 필드 | 필수 | 타입 | placeholder | 비고 |
|---|---|---|---|---|---|
| 1 | 작성자 | ✓ | text | 이름을 입력해주세요 | |
| 2 | 비밀번호 | ✓ | password | 비밀번호를 입력해주세요 | **본인확인용 (게시판 표준)** |
| 3 | 이메일 | ✓ | text@text | 이메일을 입력해주세요 / 직접 입력 | local + domain 분리 |
| 4 | 공개 여부 | ✓ | radio | — | 공개/비공개 (기본 공개) |
| 5 | 문의 유형 | ✓ | select | 문의 유형을 선택해주세요 | 옵션 미공개(기관/주제 결합 가능성) |
| 6 | 제목 | ✓ | text | 문의 내용을 입력해 주세요 | |
| 7 | 내용 | ✓ | textarea | 내용을 입력해 주세요 | |
| 8 | 개인정보 수집·이용 동의 | ✓ | checkbox | 개인정보 수집 및 이용에 동의합니다 | |

- 하단 버튼: 좌측 `목록`, 우측 `저장`
- 첨부파일/SMS·이메일 알림 옵션은 **이번 기획안에 없음** → MVP 제외, Phase 2 옵션

### 1.4 상세(타인글/게스트) (이미지 3)

- 제목, 메타 정보(카테고리·공개·작성일·작성자)
- 본문 박스(읽기 전용)
- 첨부파일/답변 영역/이전·다음/댓글 → **없음**
- 하단 `목록` 버튼만

### 1.5 상세(본인글) (이미지 4)

- 타인글 화면과 거의 동일
- **차이점: 하단에 `목록` + `수정` 버튼 추가**
- 삭제 버튼은 캡처에 없음 → MVP에서는 본인글 **수정만 허용**, 삭제는 관리자 권한으로 한정 (또는 Phase 2)
- "본인글" 판정은 **비밀번호 인증 후** 식별 (로그인 사용자 식별 의존 X)

### 1.6 상세(관리자) (이미지 5)

- 사용자 상세 화면 + **`답변` 라벨 + textarea(`답변 내용을 입력해 주세요`) + `저장` 버튼**
- 처리상태 토글(처리중 ↔ 답변완료) UI는 캡처에 명시 없음 → 답변 등록 시 자동 `답변완료` 전이로 단순화
- 작성자 정보 마스킹 없음, 평문 노출 (관리자 권한 전제)
- 비공개 글 처리도 관리자에게는 전부 보임

### 1.7 자주묻는 질문 (이미지 6)

- 검색: `카테고리` 드롭다운 / `제목` 드롭다운 + 검색창 + 돋보기
- 카테고리 가로 탭: `전체`, `라벨01~05`
- 우측: `전체` 필터 / `목록 표시 개수 10개`
- 본문: 아코디언 카드 리스트. 각 항목 좌측 `Q` 아이콘(파랑) + 질문 + 펼침 화살표
- 펼친 상태: 본문 카드 안에 `A` 아이콘(빨강) + 답변 텍스트
- 페이지네이션 동일 형태

---

## 2. 현재 구현(`onepass-support`) 스냅샷

### 2.1 백엔드 도메인 (요약)

```
src/main/java/kr/go/smes/support/
├── api/
│   ├── QnaController.java                    # /api/v1/support/qna (GET/POST)
│   ├── FaqController.java                    # /api/v1/support/faqs (+ /groups)
│   ├── AdminSupportController.java           # /api/v1/admin/support (CS 백오피스)
│   └── dto/ (Create/Detail/Summary/Answer 등 12개 record)
├── application/
│   ├── QnaService.java                       # 본인글/비밀글 정책 + 익명 처리
│   ├── FaqService.java                       # 그룹별 노출
│   ├── SupportTicketService.java             # CS 티켓 큐
│   ├── SupportRequester / CsRequester        # X-User-* / X-CS-Agent-* 헤더 기반
└── domain/
    ├── QnaPostEntity / QnaPostRepository
    ├── QnaAnswerEntity
    ├── FaqEntity / FaqRepository
    ├── FaqGroupEntity / FaqGroupRepository
    └── SupportTicketEntity / Event / PhoneConsultationEntity
```

### 2.2 현 DB 컬럼 (qna_post)

```sql
qna_id              UUID PK
tenant_id           VARCHAR(64)
agency_id           VARCHAR(64)
qna_ttl             VARCHAR(200)
qna_cn              TEXT
qna_stts_cd         VARCHAR(20)  -- OPEN/ANSWERED/CLOSED
secret_yn           CHAR(1)
anonymous_yn        CHAR(1)
writer_user_id      VARCHAR(64)
anonymous_display_name  VARCHAR(80)
anonymous_contact_email VARCHAR(120)
use_yn / 메타 4컬럼
```

### 2.3 프론트엔드

- **`onepass-fe/frontend/src/` 트리에 support / faq / inquiry / qna / board / customer 디렉토리 0개**
- 라우트 상수(`src/constants/routes.ts`)에 SUPPORT/FAQ/QNA 키 0건
- API 클라이언트, 컨테이너, 페이지 컴포넌트 **전부 미구현**

> 결론: **백엔드는 60% 완성, 프론트엔드는 0%**, 그리고 백엔드도 기획안과 **모델/필드/플로우 불일치** 다수.

---

## 3. 갭 분석 (기획안 ↔ 현재 구현)

### 3.1 데이터 모델 갭

| # | 항목 | 기획안 | 현재 | 갭/판정 |
|---|---|---|---|---|
| D1 | **비밀번호(본인확인)** | 필수 | 없음 | 🔴 **컬럼 신설 + 해시 저장 필요** |
| D2 | **카테고리/문의유형(=구분)** | 필수, 셀렉트 | `agency_id` 문자열만 | 🟠 카테고리 마스터 테이블 신설 권장 (`inquiry_category`) |
| D3 | **이메일(필수)** | local + domain 분리 | `anonymous_contact_email` (회원작성 시 NULL) | 🟠 모든 글에 필수로 승격 |
| D4 | **공개 여부(공개/비공개)** | radio | `secret_yn` | 🟢 매핑만, 정책 변경(누구나 비공개 가능) |
| D5 | **작성자(표시명)** | 필수 (회원/익명 무관) | `anonymous_display_name`만 익명 시 | 🟠 단일 `writer_name` 컬럼으로 통일 |
| D6 | **개인정보 동의** | 필수 체크 | 없음 | 🟡 `privacy_agreed_yn` + 동의 시각 컬럼 |
| D7 | **순번(번호)** | 표시 | 없음 (UUID 정렬만) | 🟠 `post_no BIGSERIAL` 추가 |
| D8 | **조회수** | 미표시이나 표준 | 없음 | 🟡 `view_cnt` 추가 (Phase 1.5) |
| D9 | **상태 라벨** | `작성중/답변완료` | `OPEN/ANSWERED/CLOSED` | 🟢 라벨 매핑(OPEN→작성중, ANSWERED→답변완료). `CLOSED`는 노출 X |
| D10 | **익명 여부 컬럼** | 불필요(전원 익명 모델) | `anonymous_yn` | 🟢 컬럼은 유지하되 항상 'Y' 또는 제거. **로그인 분리 정책에 맞춰 기본 'Y' 운영** |
| D11 | **첨부파일** | 기획안 없음 | 없음 | ⚪ MVP 제외, Phase 2 |
| D12 | **답변 1:1 관계** | 기획상 답변 1건만 노출 | 1:N (`@OneToMany`) | 🟢 1:N 구조 유지하되 UI는 최신 1건 표시 (운영 유연성 확보) |
| D13 | **FAQ 카테고리(라벨)** | 가로 탭 5개 + 전체 | `faq_group` | 🟢 `faq_group` 활용 가능, 명칭 매핑만 |
| D14 | **FAQ 검색(질문 본문)** | 검색창 + 카테고리 | 그룹 필터만 | 🟠 `faq_qstn_cn ILIKE` 추가 |

### 3.2 API/플로우 갭

| # | 항목 | 기획안 | 현재 | 갭 |
|---|---|---|---|---|
| A1 | 목록 조회 (검색·페이지) | 카테고리/제목/작성자 검색 + 페이지 | 전체 조회만 | 🔴 `Pageable` + 검색 파라미터 |
| A2 | 상세 조회(비공개) | 비밀번호 확인 후 접근 | 로그인 사용자 ID 매칭 | 🔴 비밀번호 검증 API 신설 (`POST /qna/{id}/verify`) 또는 본문 응답 시 비밀번호 헤더 검증 |
| A3 | 본인글 수정 | 수정 버튼 → 폼 → 저장 | API 없음 | 🔴 `PUT /api/v1/support/qna/{id}` + 비밀번호 검증 필수 |
| A4 | 본인글 삭제 | 캡처 없음 | API 없음 | ⚪ MVP 제외 (관리자만 삭제) |
| A5 | 익명 게시 시 헤더 | `X-User-Id` 없이 가능 | 가능(현재 anonymous 처리) | 🟢 유지 |
| A6 | 정책: 비밀글 작성 권한 | 누구나 가능 | 로그인 사용자만 가능 | 🔴 **`validateCreateRule` 제거/수정** |
| A7 | 관리자 답변 | 단일 textarea + 저장 | OK (현재 비밀답변 옵션 포함) | 🟢 익명 비밀답변 제약은 유지 |
| A8 | FAQ 검색 | 텍스트 검색 | 그룹 필터만 | 🟠 `q` 파라미터 |
| A9 | 페이지네이션 응답 형태 | `검색결과 17건` + page nav | `List<>` 단순 배열 | 🔴 `PageResponse<{items, total, page, size}>` |

### 3.3 보안/정책 갭

| # | 항목 | 현황 | 보완 |
|---|---|---|---|
| S1 | 비밀번호 저장 | 없음 | **BCrypt** 해시 저장, length(60) |
| S2 | 비밀번호 재시도 제한 | 없음 | IP + qna_id 기준 5회/10분 (Rate limit), 6회째 1시간 잠금 |
| S3 | 비밀번호 검증 결과 캐싱 | 없음 | 세션/쿠키에 짧은 `qna-access-token` (HMAC, 15분 TTL) 발급해 상세/수정 진입 |
| S4 | XSS | 본문 escape 정책 미정 | 글쓰기 시 `<script>` 등 위험 태그 sanitize (jsoup or OWASP HTML sanitizer) |
| S5 | 개인정보 동의 미보관 | 컬럼 없음 | `privacy_agreed_yn` + `privacy_agreed_at` + 동의 버전 |
| S6 | 익명 작성자 이메일 PII | 평문 저장 | 운영 환경에서 KMS 암호화(envelope) — 단 검색은 평문 필요 시 Phase 2 |
| S7 | 비공개 글 목록 노출 | 정책 미정 | 목록에는 제목 마스킹 X(노출), 본문만 비밀번호 후 공개 |
| S8 | 관리자 식별 | `X-CS-Agent-Role` 헤더 | Phase 2: 별도 Keycloak realm/client (이미 plan에 있음) |

### 3.4 프론트엔드 갭 (현재 0%)

| 영역 | 필요 산출물 |
|---|---|
| 라우트 | `/support/inquiry`, `/support/inquiry/new`, `/support/inquiry/:id`, `/support/inquiry/:id/edit`, `/support/inquiry/:id/verify`, `/support/faq` |
| 메뉴 | 좌측 사이드 `고객 센터 > 문의하기/자주묻는 질문` 메뉴 추가 |
| 페이지 컴포넌트 | InquiryListPage / InquiryWritePage / InquiryDetailPage / InquiryEditPage / InquiryPasswordPage / FaqPage |
| 공통 컴포넌트 | SupportLayout(헤더배너+사이드+안내박스+푸터), SearchToolbar, StatusBadge, Pagination, CategoryTabs, Accordion |
| API 클라이언트 | `src/api/support/inquiry.ts`, `src/api/support/faq.ts` |
| 타입 | `src/types/api/support/*.ts` (Inquiry, Faq, FaqGroup, Page) |
| 폼/검증 | react-hook-form + zod: 작성자(2~20자), 비밀번호(4~16자), 이메일 RFC, 동의 필수 |
| i18n | `public/locales/ko/support.json` + en |
| 상태 라벨 | OPEN→`작성중`, ANSWERED→`답변완료` 매핑 utility |
| 접근성 | KRDS 톤(파랑 #1B6AC1 계열, 라운드 6px, 16px 행간) |

---

## 4. 권장 아키텍처 (수정안)

### 4.1 도메인 모델 변경

```
┌─────────────────────────────────────────────────────────┐
│  inquiry_category (신설)                                 │
│   - category_id PK                                       │
│   - category_cd UNIQUE (VENTURE24/SOSANG24/SOSANG365/ETC)│
│   - category_nm (벤처24/소상24/소상공인365/기타)          │
│   - sort_sn, use_yn, 메타                                │
└─────────────────────────────────────────────────────────┘
                          │ 1:N
                          ▼
┌─────────────────────────────────────────────────────────┐
│  qna_post (재정의)                                       │
│   - qna_id UUID PK                                       │
│   - post_no BIGSERIAL  ★신설 (순번 표시)                  │
│   - category_id FK     ★신설 (구분)                       │
│   - tenant_id, agency_id  유지                            │
│   - qna_ttl VARCHAR(200)                                  │
│   - qna_cn TEXT                                           │
│   - qna_stts_cd OPEN/ANSWERED                             │
│   - secret_yn (공개 여부 - 라벨만 변경)                    │
│   - writer_nm VARCHAR(80)   ★신설 (회원/익명 통합)         │
│   - writer_email VARCHAR(120) ★필수 승격                  │
│   - writer_pwd_hash VARCHAR(60) ★신설 (BCrypt)            │
│   - privacy_agreed_yn CHAR(1) ★신설                       │
│   - privacy_agreed_at TIMESTAMPTZ ★신설                    │
│   - view_cnt INTEGER DEFAULT 0  ★신설 (Phase 1.5)         │
│   - writer_user_id (선택)  → 추후 로그인 연동 시 매핑용      │
│   - anonymous_yn / anonymous_display_name / anonymous_contact_email │
│     → DEPRECATED, 마이그레이션 V4 에서 정리                  │
│   - use_yn, 메타 4컬럼                                     │
└─────────────────────────────────────────────────────────┘
                          │ 1:N
                          ▼
┌─────────────────────────────────────────────────────────┐
│  qna_answer (기존 유지, 변경 없음)                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  qna_access_attempt (신설 - 비밀번호 시도 제한)            │
│   - attempt_id PK                                        │
│   - qna_id FK                                            │
│   - client_ip                                            │
│   - success_yn                                           │
│   - tried_at                                             │
│   - INDEX (qna_id, client_ip, tried_at)                  │
└─────────────────────────────────────────────────────────┘
```

### 4.2 API 재정의 (사용자 영역)

```
GET    /api/v1/support/categories                        # 문의유형 옵션
GET    /api/v1/support/qna?categoryCd=&searchField=&q=&page=&size=
                                                          # PageResponse<QnaSummary>
POST   /api/v1/support/qna                                # 글 등록 (공개/비공개 무관)
POST   /api/v1/support/qna/{id}/verify-password           # 비밀번호 검증 → access token 발급
GET    /api/v1/support/qna/{id}                           # 헤더 X-Qna-Access-Token (비공개 시 필수)
PUT    /api/v1/support/qna/{id}                           # 본인 수정 (access token 필수)

GET    /api/v1/support/faq/categories                     # FAQ 분류 라벨
GET    /api/v1/support/faq?categoryCd=&q=&page=&size=     # FAQ 검색/페이지
GET    /api/v1/support/faq/{id}
```

### 4.3 API (관리자 - 기존 유지 + 보완)

```
(유지)
GET    /api/v1/admin/support/qna                          # 페이지네이션 추가
POST   /api/v1/admin/support/qna/{id}/answer              # 답변 1건 + 자동 ANSWERED
PUT    /api/v1/admin/support/qna/{id}/answers/{ansId}     # 답변 수정 (Phase 1.5)
DELETE /api/v1/admin/support/qna/{id}                     # 게시글 삭제 (관리자만)

(추가)
GET    /api/v1/admin/support/categories                   # 카테고리 CRUD
POST   /api/v1/admin/support/categories
PUT    /api/v1/admin/support/categories/{id}

GET    /api/v1/admin/support/faqs                         # FAQ CRUD
POST   /api/v1/admin/support/faqs
PUT    /api/v1/admin/support/faqs/{id}
```

### 4.4 비밀번호 인증 플로우

```
[사용자] 비공개 글 클릭
   │
   ▼
[GET /qna/{id}] → 401 PASSWORD_REQUIRED
   │
   ▼
[비밀번호 모달] 입력
   │
   ▼
[POST /qna/{id}/verify-password]
   │  body: { password }
   │  rate-limit: IP+id 5회/10분
   ├── 200 → { accessToken, expiresIn: 900 } (HMAC, qnaId+exp 서명)
   └── 423 → LOCKED (6회째 잠금)
   │
   ▼
[GET /qna/{id}] with header X-Qna-Access-Token
   │
   ▼ 200 OK + 본문
   │
   ▼ (수정 시)
[PUT /qna/{id}] with same access token
```

### 4.5 응답 페이지네이션 표준

```json
{
  "items": [...],
  "page": 1,
  "size": 10,
  "total": 17,
  "totalPages": 2
}
```

---

## 5. 프론트엔드 설계

### 5.1 라우트

```typescript
// src/constants/routes.ts (추가)
SUPPORT_INQUIRY_LIST:        '/support/inquiry',
SUPPORT_INQUIRY_NEW:         '/support/inquiry/new',
SUPPORT_INQUIRY_DETAIL:      '/support/inquiry/:id',
SUPPORT_INQUIRY_EDIT:        '/support/inquiry/:id/edit',
SUPPORT_INQUIRY_PASSWORD:    '/support/inquiry/:id/verify',
SUPPORT_FAQ:                 '/support/faq',
```

### 5.2 디렉토리 구조

```
src/
├── pages/Support/
│   ├── InquiryList/
│   │   ├── index.tsx
│   │   ├── components/
│   │   │   ├── SearchToolbar.tsx
│   │   │   ├── InquiryTable.tsx
│   │   │   └── StatusBadge.tsx
│   ├── InquiryWrite/
│   │   ├── index.tsx
│   │   └── components/InquiryForm.tsx       # 등록/수정 공용
│   ├── InquiryDetail/
│   │   ├── index.tsx                         # 본인글/타인글/관리자 모두 분기
│   │   └── components/
│   │       ├── PasswordModal.tsx
│   │       └── AnswerSection.tsx
│   └── Faq/
│       ├── index.tsx
│       └── components/
│           ├── CategoryTabs.tsx
│           └── FaqAccordion.tsx
├── container/Support/
│   └── SupportLayout.tsx                     # 사이드 + 헤더 배너 + 안내박스
├── api/support/
│   ├── inquiry.ts
│   ├── faq.ts
│   └── categories.ts
├── types/api/support/
│   ├── inquiry.ts
│   ├── faq.ts
│   ├── category.ts
│   └── page.ts
├── hooks/support/
│   ├── useInquiryList.ts        # SWR/react-query
│   ├── useInquiryDetail.ts
│   ├── useFaqList.ts
│   └── useQnaAccessToken.ts     # sessionStorage 관리
└── utils/support/
    ├── statusLabel.ts            # OPEN→작성중
    └── maskEmail.ts
```

### 5.3 디자인 시스템 토큰 (기획안 추출)

```
Primary:        #1B6AC1   (헤더 배너, 주요 버튼)
Primary-hover:  #155595
Bg:             #F5F7FA   (페이지 배경)
Card:           #FFFFFF
Border:         #E5E8ED
Text-primary:   #1A1A1A
Text-secondary: #6B7280
Status-open:    #F59E0B   (작성중 - 주황)
Status-answered:#10B981   (답변완료 - 녹색)
Q icon (FAQ):   #1B6AC1
A icon (FAQ):   #DC2626
Radius:         6px (카드), 4px (입력), 9999px (badge)
```

### 5.4 폼 검증 (zod)

```typescript
const inquirySchema = z.object({
  writerName: z.string().min(2, '이름은 2자 이상').max(20),
  password: z.string().min(4, '비밀번호 4~16자').max(16)
            .regex(/^[A-Za-z0-9!@#$%^&*]+$/, '특수문자 일부만 허용'),
  emailLocal: z.string().min(1).max(64),
  emailDomain: z.string().min(1).max(64),
  secret: z.boolean(),
  categoryCd: z.string().min(1, '문의 유형을 선택해주세요'),
  title: z.string().min(2).max(200),
  content: z.string().min(10).max(10000),
  privacyAgreed: z.literal(true, { errorMap: () => ({ message: '개인정보 수집·이용에 동의해주세요' }) }),
});
```

---

## 6. 작업 분할 (Phase별)

### Phase 1 — MVP (사용자 게시판 정공법)
**목표: 기획안 6개 화면을 그대로 동작하게**

#### Backend (`onepass-support`)
- [ ] **B-01**: `V4__redesign_qna_for_board.sql` 마이그레이션
  - `inquiry_category` 테이블 생성 + 시드(`VENTURE24`/`SOSANG24`/`SOSANG365`/`ETC`)
  - `qna_post`에 `post_no BIGSERIAL`, `category_id FK`, `writer_nm`, `writer_email NOT NULL`, `writer_pwd_hash`, `privacy_agreed_yn`, `privacy_agreed_at` 추가
  - 기존 `anonymous_*` 컬럼은 유지하되 deprecated 주석
  - `qna_access_attempt` 테이블 생성
- [ ] **B-02**: `InquiryCategoryEntity`/`Repository` 추가
- [ ] **B-03**: `QnaPostEntity` 필드 추가, `CreateQnaRequest` 8필드로 재정의
- [ ] **B-04**: `QnaService.create()` 정책 변경 — 누구나 비공개 가능, 비밀번호 BCrypt 해시 저장, 동의 검증
- [ ] **B-05**: `QnaPasswordVerifyService` 신설 — BCrypt 검증 + Rate limit + HMAC access token 발급
- [ ] **B-06**: `QnaController` 엔드포인트 보강 (`/verify-password`, `PUT /{id}`, `GET /categories`)
- [ ] **B-07**: 목록 페이지네이션 + 검색 (`categoryCd`, `searchField` ∈ {title, writerNm}, `q`, `page`, `size`) — JPA `Specification` or `@Query` countQuery
- [ ] **B-08**: 상세 응답 — `secret=true && no access token`이면 401 + `error.code=E-PASSWORD-REQUIRED`
- [ ] **B-09**: FAQ 검색 보강 (`q`, `page`, `size`)
- [ ] **B-10**: `SupportRequester` 단순화 — 본인글 식별은 비밀번호로만 (서버사이드는 access token으로 판정)
- [ ] **B-11**: README 정책 갱신 (로그인 의존 제거)
- [ ] **B-12**: 단위 + 통합 테스트 (`QnaServiceIntegrationTest` 확장: 비밀번호 검증 happy/fail/lockout, 페이지/검색)

#### Frontend (`onepass-fe/frontend`)
- [ ] **F-01**: 라우트 6개 추가 + 권한 매핑(공개)
- [ ] **F-02**: `SupportLayout` 공통 컨테이너 (사이드/안내/푸터)
- [ ] **F-03**: 사이드 메뉴 `고객 센터 > 문의하기/자주묻는 질문` 추가
- [ ] **F-04**: API 클라이언트 4개 (`inquiry`, `faq`, `categories`, `password-verify`)
- [ ] **F-05**: 타입 정의 (Inquiry/Faq/Category/Page)
- [ ] **F-06**: `InquiryList` 페이지 — 검색바 + 테이블 + 페이지네이션 + `문의하기` 버튼
- [ ] **F-07**: `InquiryWrite` 페이지 — react-hook-form + zod, 카테고리 셀렉트 동적 로딩
- [ ] **F-08**: `InquiryDetail` 페이지 — 메타정보 + 본문 + 답변(있을 때만)
- [ ] **F-09**: `PasswordModal` — 비공개 글 진입 시 호출, sessionStorage에 access token 저장
- [ ] **F-10**: `InquiryEdit` 페이지 — `InquiryForm` 재사용 + access token 헤더 전송
- [ ] **F-11**: `Faq` 페이지 — 카테고리 탭 + 검색 + 아코디언
- [ ] **F-12**: i18n 사전 (`support.json`)
- [ ] **F-13**: 단위 테스트 (jest/RTL) — 폼 검증, 상태 라벨 매핑, 비밀번호 모달 흐름

### Phase 1.5 — 운영성 보강
- [ ] B-13: 조회수 (`view_cnt`) — `GET /qna/{id}` 시 atomic increment
- [ ] B-14: 답변 수정 API (`PUT /admin/support/qna/{id}/answers/{ansId}`)
- [ ] B-15: 카테고리 마스터 관리 API (관리자)
- [ ] B-16: FAQ 관리 API (관리자 CRUD)
- [ ] F-14: 본인 알림 — 답변 등록 시 이메일 발송 (SES/SMTP)
- [ ] F-15: 상태/카테고리 인디케이터 색상 — 디자인 토큰화

### Phase 2 — 고도화
- [ ] 첨부파일 (S3 presigned URL)
- [ ] 답변 다건/스레드형
- [ ] PII 암호화 (이메일/이름 envelope encryption)
- [ ] 알림 채널 — SMS / Web Push
- [ ] 감사 로그 확장 (`support_audit_log` 활용)
- [ ] CS 백오피스 별도 Keycloak realm (기존 plan 합류)
- [ ] 다국어 (영문 라벨, MOSS 외)

---

## 7. 마이그레이션 전략

### 7.1 데이터 호환

기존 운영 데이터가 있다면(없으면 패스):
1. `writer_nm` ← `anonymous_display_name`(NOT NULL 이전 데이터는 'UNKNOWN' fallback)
2. `writer_email` ← `anonymous_contact_email` (회원작성은 별도 lookup 필요)
3. `category_id` ← `agency_id` 매핑 시드 (`VENTURE24` 등)
4. `writer_pwd_hash` ← 회원작성 글에는 자동 임시 비밀번호 발급 + 이메일 통지(별도 배치)
5. 컬럼 추가는 nullable로 우선 적용 → 백필 → NOT NULL 승격(V5)

### 7.2 API 호환

기존 사용자 API(`POST /api/v1/support/qna`)는 **DTO 추가 필드를 옵셔널로 잠시 허용**한 뒤, 프론트엔드 배포 완료 후 `@NotBlank` 승격. 두 번에 나눠 릴리즈.

---

## 8. 리스크 / 결정 필요 사항

| # | 항목 | 옵션 | 권장 |
|---|---|---|---|
| R1 | 비밀번호 정책 | (a) 4~16 영문+숫자 (b) 8자 이상 복합 | (a) — 게시판 본인확인용, 정보가치 낮음 |
| R2 | access token 저장 | sessionStorage / httpOnly cookie | httpOnly cookie 권장 (XSS 안전), 단 도메인 동일 정책 필요 |
| R3 | 비밀번호 분실 | 분실 시 절차 없음 vs 이메일 재설정 | Phase 1: 절차 없음(공지문구), Phase 1.5: 이메일 재설정 링크 |
| R4 | 비공개 글 목록 | 제목 노출 vs 마스킹 | **제목 노출** (기획안 캡처 기준 자물쇠/마스킹 없음) |
| R5 | 카테고리 마스터 | enum 코드 / DB 테이블 | DB 테이블(`inquiry_category`) — 추후 관리자 화면에서 CRUD 가능 |
| R6 | 헤더 사용자 인사말 | 로그인 의존 없음 정책과 충돌 | **GNB 위젯 분리** — 게시판 본문은 헤더 상태와 독립 |
| R7 | 푸터 정보 | 기관/연락처 고정 텍스트 | i18n 사전 + 환경 변수로 분리 (배포 환경 별 조정) |
| R8 | 검색 인덱스 | 단순 ILIKE vs Postgres FTS | Phase 1: ILIKE + GIN trigram (`pg_trgm`) Phase 2: FTS |
| R9 | 본인글 삭제 | 캡처에 없음 — MVP 제외 vs 포함 | **MVP 제외**, 관리자 삭제만. Phase 1.5 재검토 |

---

## 9. 완료 정의 (Definition of Done)

### Phase 1 DoD
- [x] **백엔드**
  - DB 마이그레이션 V4 적용 완료, 모든 테스트 그린
  - 사용자/관리자 API 통합 테스트 추가 (페이지/검색/비밀번호 happy+sad path)
  - OpenAPI 문서(`/swagger-ui.html`)에서 모든 신규 엔드포인트 노출
  - 비밀번호 BCrypt 해시 검증 — 평문 저장 0건
  - Rate limit 동작 확인 (5회 실패 시 423)
- [x] **프론트엔드**
  - 6개 화면 라우트 동작 (목록/글쓰기/상세/비밀번호모달/본인글수정/FAQ)
  - 모든 폼 zod 검증, 에러 메시지 한글
  - 페이지네이션 + 검색 동작
  - i18n 한/영 사전 완비
  - jest 단위 테스트 ≥ 80% (페이지/훅/유틸)
  - actionlint/typecheck/lint 통과
- [x] **문서**
  - 본 문서(이 파일) 갱신 — 완료 항목 체크
  - `docs/onepass-support-cs-backoffice-plan.md` Phase 2 연결성 갱신
  - `onepass-support/README.md` 정책 갱신(로그인 분리)

---

## 10. 작업 순서 추천 (PR 분할)

PR을 작게 쪼개야 리뷰가 빠릅니다.

1. **PR A** (1d): 본 문서 + DB 마이그레이션 V4 + `InquiryCategoryEntity` + seed
2. **PR B** (2d): `QnaPostEntity` 확장 + `CreateQnaRequest` 재정의 + 정책 변경 + 통합 테스트
3. **PR C** (1d): 비밀번호 검증/토큰 발급 (`QnaPasswordVerifyService`) + Rate limit
4. **PR D** (1d): 페이지네이션 + 검색 + FAQ 검색
5. **PR E** (1d): `PUT /qna/{id}` + 본인글 수정
6. **PR F** (2d): 프론트 라우트/레이아웃/메뉴/API 클라이언트
7. **PR G** (2d): 프론트 페이지 4개(List/Write/Detail/Edit) + 폼
8. **PR H** (1d): 프론트 FAQ + i18n
9. **PR I** (1d): 단위 테스트 보강 + 디자인 토큰 적용 + 접근성 점검
10. **PR J** (0.5d): Phase 1.5 준비 (조회수/카테고리 관리 API 골격)

**총 예상**: 백엔드 5d + 프론트 6d + 테스트/문서 1d = **약 12 영업일 (2.5주)**, 페어 작업 시 1.5주

---

## 11. 비기획 의문점 (사용자 확인 요청)

> 기획안 캡처에서 식별 불가하여 결정이 필요한 항목

1. **본인글 삭제 가능 여부** — MVP에서 제외 권장. 동의?
2. **첨부파일 필요 여부** — 기획안 없음. Phase 2로 미루는 게 맞나?
3. **답변 알림 채널** — 이메일만 vs SMS+이메일? (이메일이 필수 필드이므로 이메일 우선 권장)
4. **카테고리 옵션의 정식 명칭** — `벤처24/소상24/소상365/기타` 외 추가가 있는지? `소상공인365`로 통일?
5. **상태값** — `작성중/답변완료` 2개로 끝인지, `대기/처리중/답변완료/종결` 4단계로 갈지? (현재 DB는 OPEN/ANSWERED/CLOSED 3단계 → 사용자 UI는 2단계 매핑 권장)
6. **비밀번호 재설정 절차** — Phase 1에서는 비밀번호 분실 시 신규 등록 안내로 처리해도 되는지?
7. **공지사항 게시판** — 기획안에 없음. 별도 모듈로 가는지? (본 문서 범위 외)
8. **사용자 로그인 헤더 위젯** — 게시판이 로그인과 무관해도 화면 상단에 인사말이 보임. 헤더는 별도 GNB 위젯으로 처리하는 게 맞는가?

---

## 12. 참고 자료

- 기획 이미지 6장 (압축 해제 위치: `/tmp/board-images/decoded/`)
- 현재 DB 스키마: `onepass-support/src/main/resources/db/migration/V1~V3__*.sql`
- 현재 API: `onepass-support/README.md`
- 기존 CS 백오피스 plan: `docs/onepass-support-cs-backoffice-plan.md`
- KRDS(공공기관 표준 디자인 시스템): https://www.krds.go.kr (참고)
- BCrypt: Spring Security `BCryptPasswordEncoder`

---

## 변경 이력

- **2026-05-26 v1.0** — 최초 작성 (기획안 6장 분석 + 갭 식별 + Phase별 작업 분할)
