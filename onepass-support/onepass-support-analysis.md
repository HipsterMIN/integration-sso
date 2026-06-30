# OnePass Support 서비스 상세 분석 보고서 (onepass-support)

본 문서는 SMES(중소벤처기업부) OnePass 통합 인증 플랫폼의 고객 지원 및 CS 백오피스 관리를 전담하는 **`onepass-support`** 마이크로서비스 모듈에 대한 설계 및 기술 분석 보고서입니다.

---

## 1. 개요 (Overview)

`onepass-support`는 OnePass 통합 인증 서비스의 이용자 대상 고객 지원 도메인(FAQ, Q&A) 및 운영자 대상 CS 백오피스 통합 티켓 관리 시스템을 제공하는 핵심 API 모듈입니다.

- **프로젝트 유형**: Spring Boot Web Application
- **주요 언어**: Java 17
- **빌드 도구**: Gradle (Kotlin DSL)
- **주요 프레임워크/기술**: Spring Boot 3.x, JPA (Hibernate), PostgreSQL, Flyway, Springdoc OpenAPI (Swagger UI)
- **외부 종속성**: 프로젝트 공통 모듈인 `:platform-common`을 포함

---

## 2. 도메인 정책 및 권한 매트릭스 (Domain Policy & Security Matrix)

OnePass 지원 도메인은 일반 사용자 유형과 운영자 역할군에 따라 세분화된 접근 제어 정책을 적용합니다.

### 2.1. 일반 사용자 (Support User)
사용자는 인증 여부(SSO/IM 로그인 완료 여부)에 따라 익명 사용자 또는 로그인 사용자로 구분됩니다.

| 사용자 구분 | Q&A 목록/단건 조회 | Q&A 신규 작성 | Q&A 비밀글 작성 | 답변 비밀글 조회 |
| :--- | :--- | :--- | :--- | :--- |
| **익명 사용자** | 공개글만 조회 가능 | 가능 (표시 이름 필수) | **불가** | **불가** |
| **로그인 사용자** | 공개글 + **본인 비밀글** 조회 | 가능 | **가능** | **본인 글의 비밀 답변**만 가능 |

> **💡 핵심 규칙**
> - 익명 문의글에는 운영자가 답변을 달 때 **비밀 답변으로 설정할 수 없습니다** (항상 공개 답변만 허용).
> - 비밀글 및 비밀 답변의 실제 내용(Content)은 소유권 검증 및 관리자 여부를 엄격하게 체크하여, 불법적인 데이터 유출을 원천적으로 차단합니다.

### 2.2. CS 백오피스 운영자 (CS Staff & Admin)
CS 백오피스는 SSO/IM 로그인과 분리된 별도의 CS 인증 체계(Phase 1은 전용 HTTP 헤더 `X-CS-Agent-Id` / `X-CS-Agent-Role` 사용)를 통해 운영됩니다.

| 역할 코드 (Role) | 주요 권한 설명 | 스태프 여부 (`staff`) | 쓰기 권한 (`writable`) | 승인 권한 (`lead`) |
| :--- | :--- | :---: | :---: | :---: |
| **`CS_AGENT`** | 일반 상담원 (티켓 관리, 전화 상담 등록, Q&A 답변 작성) | ✅ | ✅ | ❌ |
| **`CS_LEAD`** | 상담 팀장 (티켓 담당자 배정, 티켓 상태 강제 조정 등) | ✅ | ✅ | ✅ |
| **`SUPPORT_ADMIN`**| 최고 관리자 (모든 CS 권한 소유) | ✅ | ✅ | ✅ |
| **`AUDITOR`** | 감사원 (티켓 및 Q&A 읽기 전용 모니터링) | ✅ | ❌ | ❌ |

---

## 3. 데이터베이스 및 엔티티 설계 (Database & Entity Design)

PostgreSQL 내의 `support` 스키마를 단독으로 활용하며, **공공기관 DB 표준화 지침**에 맞춘 메타 데이터 컬럼 구조를 가지고 있습니다.

### 3.1. DB 공통 메타 컬럼
모든 중요 테이블은 데이터 이력 관리 및 변경 추적을 위해 아래 필드를 필수 탑재합니다.
- `use_yn` (사용 여부, `Y/N`)
- `frst_regist_pnttm` / `frst_register_id` (최초 등록 시각 / 등록자 ID)
- `last_updt_pnttm` / `last_updusr_id` (최종 수정 시각 / 수정자 ID)

### 3.2. 핵심 스키마 구조 (Flyway Migration 기준)

#### A. FAQ 도메인
- `faq_group`: FAQ 대분류 정보를 구성합니다. (예: `LOGIN`, `ACCOUNT`, `CERTIFICATE`)
- `faq`: 그룹별 질문 및 답변 마크다운/텍스트 데이터를 저장하며, 노출 여부(`expsr_yn`)와 수동 정렬 순번(`sort_sn`)을 가집니다.

#### B. Q&A 도메인
- `qna_post`: 사용자가 올린 질문 테이블. 비밀글 여부(`secret_yn`), 익명 여부(`anonymous_yn`), Q&A 상태 코드(`qna_stts_cd`: `OPEN`, `ANSWERED`, `CLOSED`)를 관리합니다.
- `qna_answer`: 관리자가 작성한 답변 테이블. 답변 내용(`answer_cn`), 비밀답변 여부(`secret_yn`)를 관리합니다.

#### C. CS 백오피스 티켓 도메인
- `support_ticket`: 통합 민원 관리 창구. Q&A 채널 및 전화 상담(`PHONE`) 등을 하나의 큐(Queue)로 통합 관리합니다.
  - 접수 채널(`channel_cd`): `QNA`, `PHONE`, `EMAIL`, `CHAT`, `MANUAL`
  - 티켓 상태(`ticket_stts_cd`): `OPEN`, `PENDING`, `ANSWERED`, `ESCALATED`, `CLOSED`
  - 우선순위(`priority_cd`): `LOW`, `NORMAL`, `HIGH`, `URGENT`
- `support_ticket_event`: 티켓 내부 타임라인 변경 이력을 기록하는 이벤트 스트림 테이블. 공개 범위(`visibility_cd`: `CUSTOMER`, `INTERNAL`)에 따라 외부 고객 노출 여부를 차단/허용합니다.
- `support_phone_consultation`: 인바운드/아웃바운드 전화 상담 내용, 본인확인 여부(`identity_verified_yn`), 조치 요청 사항을 정밀 기록하는 상세 테이블입니다.

#### D. 시스템 감사 도메인
- `support_audit_log`: 주요 변경 행위(Q&A 등록, FAQ 변경 등)에 대한 IP 주소 및 User-Agent 기록 등을 담는 독립적인 감사 로그 테이블입니다.

---

## 4. 아키텍처 및 내부 컴포넌트 분석

`onepass-support`는 전통적인 Layered Architecture 레이어 설계를 따르면서도, 비즈니스 요건에 맞춰 도메인 간의 유연한 연동을 보장하도록 구현되어 있습니다.

```
+-------------------------------------------------------------+
|                     1. Presentation Layer                   |
|   - FaqController, QnaController                            |
|   - AdminSupportController (CS Backoffice)                  |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                       2. Service Layer                      |
|   - FaqService, QnaService                                  |
|   - SupportTicketService (티켓/타임라인 연계 비즈니스 로직)       |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                     3. Infrastructure Layer                 |
|   - Spring Data JPA Repositories (QnaPostRepository 등)     |
|   - PostgreSQL DB Migration (Flyway V1, V2, V3)             |
+-------------------------------------------------------------+
```

### 4.1. 주요 연동 흐름 (Ticket Pipeline)
- **Q&A 접수 시 자동 티켓팅 연계**:
  사용자가 홈페이지에서 Q&A 글을 새로 등록하면, `QnaService`가 생성 로직을 마친 후 즉시 `SupportTicketService.createFromQna()`를 트랜잭션 내에서 호출합니다. 이를 통해 `support_ticket`에 새로운 `QNA` 채널 티켓이 발급되고, 타임라인에 `QNA_CREATED`라는 고객 노출형(`CUSTOMER`) 이벤트가 자동 추가됩니다.
- **Q&A 답변 시 티켓 자동 종결/업데이트**:
  운영자가 `AdminSupportController`를 통해 Q&A 답변을 작성하면, `QnaService`는 `qna_answer`를 등록한 후 `SupportTicketService.recordQnaAnswer()`를 호출하여 연계된 CS 티켓의 상태를 자동으로 `ANSWERED`로 바꾸고 타임라인에 `PUBLIC_REPLY` 이벤트를 기록합니다.

---

## 5. API 설계 상세 (API Specifications)

### 5.1. 사용자용 (Public Support API)
- **FAQ 목록 조회**
  - `GET /api/v1/support/faqs` (특정 그룹 코드 필터링 가능)
  - `GET /api/v1/support/faqs/groups` (FAQ 그룹 목록 조회)
- **Q&A 질문 등록 및 목록 조회**
  - `GET /api/v1/support/qna` (로그인 정보 바탕 보안 노출 제어)
  - `POST /api/v1/support/qna` (신규 Q&A 접수)
  - `GET /api/v1/support/qna/{id}` (단건 상세 조회, 본인인증/비밀글 체크 탑재)

### 5.2. 운영자/CS용 (CS Backoffice API)
- **Q&A 관리**
  - `GET /api/v1/admin/support/qna` (관리자용 전체 Q&A 목록 조회)
  - `POST /api/v1/admin/support/qna/{id}/answer` (Q&A 답변 등록)
- **티켓 큐(Queue) 관리**
  - `GET /api/v1/admin/support/tickets` (다차원 동적 쿼리 기반 티켓 통합 큐)
  - `GET /api/v1/admin/support/tickets/{id}` (티켓 상세 및 이벤트 타임라인 전체 조회)
  - `PATCH /api/v1/admin/support/tickets/{id}/assignment` (담당 상담원 지정 - `CS_LEAD` 이상 권한 필요)
  - `PATCH /api/v1/admin/support/tickets/{id}/status` (티켓 상태 수동 조정)
- **전화 상담 및 내부 조치**
  - `POST /api/v1/admin/support/phone-consultations` (전화 상담 수기 입력 및 연동 티켓 발행)
  - `POST /api/v1/admin/support/tickets/{id}/internal-notes` (타임라인 내 내부 메모 작성)

---

## 6. 테스트 구조 및 안정성 (Testing Strategy)

`onepass-support`는 통합 테스트 기반의 안정성 확보 방식을 채택하고 있습니다.

- **테스트 디렉토리**: `onepass-support/src/test`
- **핵심 구성 파일**:
  - `SupportIntegrationTestBase.java`: `SpringBootTest` 환경을 초기화하고 테스트용 인메모리 H2 데이터베이스 구조를 매핑하는 베이스 클래스입니다.
  - `FaqServiceIntegrationTest.java`: FAQ 분류 조회 및 정렬, 상세 필터 로직 검증.
  - `QnaServiceIntegrationTest.java`: 익명 비밀글 제한, 로그인 본인글 검증, 익명 질문의 답변 보안 정책 등 복합 시나리오 검증.
  - `SupportTicketServiceIntegrationTest.java`: 전화 상담 등록, 티켓 담당자 배정 시 권한에 따른 분기 차단 기능, 내부 메모 및 타임라인 상태 갱신 자동화 테스트.

---

## 7. 향후 발전 과제 (Next Steps)

1. **인증 인프라 연동 강화 (Phase 2)**:
   현재는 경계 분리를 위해 HTTP 요청 헤더(`X-CS-Agent-Id`, `X-CS-Agent-Role`) 수동 파싱을 사용하는 임시 아키텍처를 취하고 있습니다. 상용 서비스 전환 시 Keycloak 클라이언트 설정 또는 자체 IAM을 통한 Spring Security 연동을 완료해야 합니다.
2. **동적 멀티테넌트 및 다국어 지원**:
   `tenant_id` 컬럼과 `agency_id` 컬럼이 스키마 레벨에 완비되어 있으므로, 추후 각 기관별/테넌트별 독립적인 FAQ 셋과 티켓 큐 필터링을 완벽하게 격리 노출하는 컨트롤러 커스텀이 용이합니다.
