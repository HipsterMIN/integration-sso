# onepass-support

OnePass 지원 도메인 API 모듈입니다.

## 책임

- FAQ 조회/관리
- Q&A 작성/답변/상태 관리
- 첨부파일 메타데이터 관리
- 게시판 운영 감사 로그

## 경계

이 모듈은 인증 핵심 경로(`q-sign`, `q-im`, `ido`)와 분리합니다. 게시판 장애나 검색/첨부파일 부하가 로그인, handoff, 통합회원 처리에 영향을 주지 않도록 독립 배포 단위로 유지합니다.

## DB

1차 SI 운영 기준은 PostgreSQL `onepass` DB의 `support` schema를 사용합니다.

```text
jdbc:postgresql://<host>:5432/onepass?currentSchema=support
```

장기적으로는 고객사/테넌트별 분리 요구에 따라 `onepass_support` DB 또는 tenant partition 구조로 확장할 수 있습니다.

## API 초안

```text
GET    /api/v1/support/faqs
GET    /api/v1/support/faqs/{id}
GET    /api/v1/support/qna
POST   /api/v1/support/qna
GET    /api/v1/support/qna/{id}

GET    /api/v1/admin/support/qna
POST   /api/v1/admin/support/qna/{id}/answer
```

현재 PR은 모듈 뼈대만 제공합니다. 인증/인가, 실제 CRUD, 첨부파일 저장소 연동은 후속 작업에서 구현합니다.
