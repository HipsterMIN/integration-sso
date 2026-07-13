# 11 · Module Deep Dive — onepass-agent + onepass-agency-sdk

> 두 모듈은 **기관측 통합(외부 배포)** 을 담당하므로 결합 문서로 정리.
> 원천: `docs/onepass-agent-*.md` (5종) + `docs/onepass-agency-sdk-usage-guide.md`.

---

## Part A. onepass-agent (M11) — JVM Java Agent

### A.1 삼각 요약
| 항목 | 값 |
|---|---|
| 파일 수 | 25 |
| 배포 형태 | JAR (-javaagent 로드) |
| 목적 | 유관기관 WAS 무수정 자동 SSO 연동 |
| 핵심 기술 | Java Agent + Byte Buddy 위빙 (JEUS 지원) |

### A.2 서브패키지
```
onepass-agent/src/main/java/kr/go/smes/agent/
├── config/           — Agent 설정
├── core/             — Agent 코어 (Premain / Agentmain)
├── http/             — HTTP 클라이언트 (IdO 호출)
├── was/              — WAS 감지 (JEUS/Tomcat/WebLogic)
└── weaving/
    ├── engine/       — Byte Buddy 위빙 엔진
    └── jeus/         — JEUS 전용 위버
```

### A.3 정본 문서 5종 (`docs/`)
| 문서 | 목적 |
|---|---|
| `onepass-agent-index.md` | 전체 인덱스 |
| `onepass-agent-integration-guide.md` | 통합 가이드 |
| `onepass-agent-walkthrough.md` | 실전 워크스루 |
| `onepass-agent-troubleshooting.md` | 트러블슈팅 |
| `docs/internal/architecture/onepass-agent-architecture.md` | 아키텍처 심층 |
| `docs/internal/architecture/jeus-sso-deep-dive.md` | JEUS 위빙 상세 |

### A.4 배포 흐름
```
기관 WAS 기동:
  java -javaagent:/opt/onepass-agent.jar \
       -Donepass.ido.url=https://ido.example.com \
       -Donepass.agency.code=SMES-001 \
       -Donepass.api.key=... \
       -jar their-app.jar
    │
    Agent Premain 진입:
      1. WAS 감지 (JEUS 우선)
      2. 대상 클래스 로드 훅 등록
      3. Byte Buddy 로 Filter/Interceptor 위빙
    │
    런타임:
      Request → 위빙된 필터 → IdO 세션 확인 → 없으면 SSO 리디렉트
```

### A.5 테스트베드
`onepass-agent-testbed/` (settings.gradle.kts 미포함 별도 트리):
- `apps/` — 테스트용 WAS 앱
- `agent/` — 에이전트 빌드
- `docker/` — 격리 환경
- `config/` — 시나리오 설정
- `scripts/` — 실행 스크립트

---

## Part B. onepass-agency-sdk (M9) — Java SDK

### B.1 삼각 요약
| 항목 | 값 |
|---|---|
| 파일 수 | 13 |
| 배포 형태 | Maven/Gradle 의존성 JAR |
| 목적 | 기관 서버가 IdO 를 호출하는 표준 클라이언트 |
| 정본 가이드 | `docs/onepass-agency-sdk-usage-guide.md` |

### B.2 서브패키지
```
onepass-agency-sdk/src/main/java/kr/go/smes/sdk/agency/
├── exception/       — SDK 예외 (PlatformErrorCode 매핑)
├── http/            — HTTP 클라이언트 (재시도·타임아웃)
├── idempotency/     — Idempotency-Key 유틸
├── model/           — Request/Response 모델
└── security/        — HMAC 서명, 헤더 생성
```

### B.3 SDK vs Agent 선택 기준
| 상황 | 권장 |
|---|---|
| 신규 개발 (Java) | **SDK** 사용 (명시적 통합) |
| 신규 개발 (다른 언어) | REST API 직접 호출 |
| 레거시 JEUS/Tomcat 무수정 통합 | **Agent** 사용 (자동 위빙) |
| 부분 통합 (특정 엔드포인트만) | SDK |

### B.4 SDK 사용 예 (문서 요약)
```java
OnePassAgencyClient client = OnePassAgencyClient.builder()
    .idoBaseUrl("https://ido.example.com")
    .agencyCode("SMES-001")
    .apiKey(System.getenv("ONEPASS_API_KEY"))
    .hmacSecret(System.getenv("ONEPASS_HMAC_SECRET"))
    .build();

HandoffVerifyResponse resp = client.handoff().verify(handoffToken);
if (resp.isValid()) {
    List<String> roles = resp.roles();   // ★PR #205 이후 사용 가능
    // ...
}
```

---

## Part C. 결합·경계

| 항목 | Agent | SDK |
|---|---|---|
| 침습성 | 높음 (byte-code weaving) | 낮음 (명시적 호출) |
| 대상 언어 | JVM 만 | JVM 만 |
| 배포 | -javaagent | Gradle/Maven |
| 로깅 | Agent 자체 로그 | 앱 로그 |
| SDK 재사용 | Agent 도 SDK 를 내부 사용 (관찰 필요) | — |

---

## 관찰된 리스크

| # | 항목 | 위험 | 근거 |
|---|---|---|---|
| L1 | JEUS 위빙은 벤더 특화 — WAS 업그레이드 시 회귀 위험 | 🔴 HIGH | jeus-sso-deep-dive.md |
| L2 | SDK 버전 호환 매트릭스 미정본화 | 🟡 MED | agency-sdk-usage |
| L3 | Agent 시작 실패 시 WAS 기동 여부 (fail-open vs fail-closed) | 🟡 MED | agent core |
| L4 | q-authz `roles[]` 를 SDK 응답에서 노출하는 계약 문서화 필요 | 🟡 MED | PR #205 후 |
| L5 | SDK 가 Idempotency-Key 자동 생성/재전송 정책 | 🟢 LOW | idempotency 패키지 |

---

## 참조
- Agent 문서군 (5종) — `docs/onepass-agent-*.md`
- SDK 가이드 — `docs/onepass-agency-sdk-usage-guide.md`
- 아키텍처 — `docs/internal/architecture/onepass-agent-architecture.md`, `jeus-sso-deep-dive.md`
- 테스트베드 — `/home/user/webapp/onepass-agent-testbed/`
