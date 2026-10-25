# 07 · Module Deep Dive — agency-stub (유관기관 시뮬레이터)

> 원천: `/home/user/webapp/agency-stub/` · 정본 `docs/internal/spec/03e-module-agency-stub.md`.
> 파일 수 38 · Postgres `agency` 스키마 · V1~V2.

---

## 1. 삼각 요약

| 항목 | 값 |
|---|---|
| 포트 | 8084 |
| DB | PostgreSQL 16 (schema: `agency`) |
| 마이그레이션 | V1~V2 (최신 `V2__add_webhook_and_api_key.sql`) |
| 파일 수 | 38 |
| 역할 | 유관기관(SP) **E2E 시뮬레이터** — 실기관 없이 통합 흐름 검증 |
| 컨트롤러 | 9개 |

---

## 2. 서브패키지 (10개)

```
agency-stub/src/main/java/kr/go/smes/agency/
├── api/          — REST 컨트롤러 진입점
├── client/       — IdO 로 향하는 HTTP 클라이언트
├── config/       — Bean 설정 (Kafka Producer, HMAC)
├── init/         — 시드 데이터/초기화
├── kafka/        — Kafka 컨슈머 (qim.user.events, qim.sp.member.events 발행)
├── mock/         — Mock 응답 (기관 API 시뮬레이션)
├── session/      — 세션 관리 (에이전시 자체 세션)
├── simulator/    — 시나리오 실행기 (예: 회원 가입 시뮬)
└── webhook/      — Webhook 수신 (IdO → 기관 콜백)
```

---

## 3. 마이그레이션 V1~V2

| V | 파일 | 요지 |
|---|---|---|
| V1 | `create_schema.sql` | 기관 스터브 기본 테이블 |
| V2 | `add_webhook_and_api_key.sql` | 웹훅 수신 로그 + API Key |

---

## 4. E2E 시나리오 자동화 (PR #191 `9bdff9d`)

이미 자동화된 E2E: `agency-stub → ido → q-sign → ido → handoff → agency`
- 완결 시나리오 1건 (Release NO-GO 위험 #3 해소).
- 컨트롤러 진입: `AgencyController.startScenario(...)` (관찰).
- 결과 페이로드: Handoff verify 후 `HandoffPayload.roles[]` 포함 여부 확인 지점.

---

## 5. 역방향 이벤트 발행자

`kafka/` 에서 SP → Q-IM 통지용 `qim.sp.member.events` 발행 로직 존재. 이는 SP가 자체 회원 상태(예: 정지·복권)를 Q-IM 에 통지하는 표준 흐름.

---

## 6. Webhook 흐름

```
IdO ─(POST /webhook/agency-events)→ agency-stub
         │
         (HMAC 서명 헤더 검증)
         │
         (기관 시뮬레이션 로직 실행)
         │
         응답 or 202 (async)
```

---

## 7. 관찰된 리스크

| # | 항목 | 위험 | 근거 |
|---|---|---|---|
| L1 | 운영 배포 시 agency-stub 노출 금지 | 🔴 HIGH | 시뮬레이터 특성 |
| L2 | Mock 로직이 실기관 로직과 괴리 가능 | 🟡 MED | mock/ 디렉터리 |
| L3 | q-authz L2 SCIM Groups 소비 미착수 (SCIM 클라이언트 없음) | 🟡 MED | grep 결과 |

---

## 8. 참조
- 소스: `/home/user/webapp/agency-stub/`
- 스펙: `docs/internal/spec/03e-module-agency-stub.md`
- SDK 동반: `docs/onepass-agency-sdk-usage-guide.md`
