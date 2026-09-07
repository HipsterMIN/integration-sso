# 07. idem-console 프론트엔드 모듈 구현 상태 (v1.9.0)

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09  
> **모듈 경로**: `idem-console/`  
> **포트**: 3001  
> **기술 스택**: React 18, Vite, Node.js 20+

---

## 1. 모듈 개요

idem-console는 **OnePass 통합인증 플랫폼의 SPA(Single Page Application) 프론트엔드**이다.

> ⚠️ **현황**: BFF(Backend for Frontend) Spring Boot 서버가 **제거**되었으며, FE Advisory 처리는 IdO 모듈로 이관되었다.

---

## 2. 디렉토리 구조

```
idem-console/
├── src/
│   ├── components/        # 공통 컴포넌트
│   ├── pages/             # 페이지 컴포넌트
│   │   ├── auth/          # 인증 흐름 UI
│   │   └── callback/      # OAuth 콜백 처리
│   ├── services/          # API 클라이언트
│   ├── store/             # 상태 관리
│   └── main.jsx
├── public/
├── index.html
├── vite.config.js
└── package.json
```

---

## 3. 핵심 기능

### 3.1 인증 UI 흐름

```
사용자 접속
    │
    ▼
Provider 선택 화면 (카카오/네이버/PASS/GPKI 등)
    │
    ▼
IdO BrokerService → 인가 URL 생성 (PKCE 포함)
    │
    ▼
IdP 리디렉션 → 인증 완료 → FeSession 발급
    │
    ▼
기관 서비스 선택 → Handoff Ticket 발급 → 기관 진입
```

### 3.2 FeSession 기반 상태 관리

- IdO `FeSessionController`와 통신
- FeSession: Redis TTL 30분 슬라이딩, 절대 만료 8시간
- SessionAdvisory 이벤트 수신 시 강제 무효화 (FeAdvisoryConsumer → IdO)

### 3.3 IdO API 연동

| API | 용도 |
|-----|------|
| `POST /api/v1/fe-session` | FE 세션 생성 |
| `GET /api/v1/fe-session/{id}` | 세션 조회 |
| `DELETE /api/v1/fe-session/{id}` | 세션 무효화 |
| `POST /api/v1/broker/init` | 브로커 인증 시작 |
| `POST /api/v1/handoff/issue` | Handoff Ticket 발급 |

---

## 4. 구현 완성도

| 기능 | 상태 | 비고 |
|------|------|------|
| Provider 선택 UI | ✅ | 기본 구현 |
| OAuth 콜백 처리 | ✅ | 기본 구현 |
| FeSession 관리 | ✅ | IdO 연동 |
| 기관 서비스 진입 | ✅ | Handoff Ticket 발급 |
| 회원 전환 UI | ❌ | P2 미구현 |
| 관리자 UI | ❌ | P3 미구현 |

---

## 5. 빌드 및 실행

```bash
# 개발 서버
cd idem-console
npm install
npm run dev  # port 3001

# 프로덕션 빌드
npm run build
```

### Docker 구성

```yaml
idem-console:
  image: idem-console:latest
  ports:
    - "3001:3001"
  networks:
    idem-net:
      ipv4_address: 172.20.0.20
  profiles:
    - app
```

---

## 6. 잔여 미구현 항목

| 항목 | 우선순위 |
|------|---------|
| 회원 가입/전환 UI (PPTX 프로세스 매핑) | P2 |
| 개인정보 동의 UI | P2 |
| 회원정보 관리 UI | P3 |
| 관리자 Admin Console UI | P3 |
| E2E 테스트 (Playwright) | Sprint 6 |

---

*다음 문서: [08-database-schema.md](08-database-schema.md)*
