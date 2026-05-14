# OnePass 플랫폼: 데이터 안전 보장 및 메시지 전달 체계 가이드 (관리자/사무관용)

**문서 ID**: ARCH-KAFKA-MGR-001  
**작성일**: 2026-05-14  
**대상 독자**: 프로젝트 담당 사무관, 비즈니스 기획자, 프로젝트 관리자(PM)

---

## 1. 도입: 데이터는 어떻게 유실 없이 안전하게 전달되는가?

OnePass 플랫폼은 수백만 국민의 인증과 회원 정보를 다루는 국가 핵심 인프라입니다. 사용자가 로그인을 하거나 회원 정보를 수정할 때, 내부의 여러 시스템(인증 시스템, 회원 시스템, 기관 연동 시스템 등)은 서로 정보를 주고받아야 합니다.

이때 시스템 간에 직접 통신을 하게 되면, 한 시스템에 장애가 발생할 경우 다른 시스템도 멈춰버리는 **도미노 현상**이 발생할 수 있습니다.

이를 방지하기 위해 OnePass는 **Kafka(카프카)**라는 **"대용량 사내 게시판(메시지 버스)"**을 도입했습니다. 각 시스템은 서로 직접 연락하지 않고, 이 게시판에 "회원 가입 완료", "인증 완료" 등의 메모만 남기면 됩니다.

하지만 게시판에 메모를 붙이러 가는 길에 넘어지거나(네트워크 오류), 게시판이 공사 중(Kafka 서버 장애)이라면 어떻게 될까요? OnePass는 이러한 극한의 장애 상황에서도 **단 한 건의 데이터 유실도 허용하지 않는 "안전한 우편함(Outbox) 패턴"**을 적용했습니다.

---

## 2. 한 눈에 보는 비교: 일반 방식 vs OnePass의 안전한 방식

### 2.1 일반적인 시스템의 위험한 방식 (데이터 유실 가능성 존재)

일반적인 시스템은 업무 처리 후 바로 게시판(Kafka)에 메시지를 보내려 시도합니다. 이때 통신 장애가 발생하면 DB에는 정보가 저장되었지만, 다른 시스템에는 전달되지 않는 **'배달 사고'**가 발생합니다.

```mermaid
sequenceDiagram
    actor 사용자
    participant SYS as 회원 시스템 (Q-IM)
    participant DB as 데이터베이스
    participant KAFKA as 사내 게시판 (Kafka)

    사용자->>SYS: 회원 정보 수정 요청
    SYS->>DB: ① 정보 수정 저장 (성공 ✅)
    SYS--xKAFKA: ② 게시판에 알림 전송 시도 (통신 장애로 실패 💥)

    note over DB,KAFKA: ⚠️ 데이터 불일치 발생!<br/>DB는 수정 완료, 다른 시스템은 여전히 구버전 정보 보유
```

### 2.2 OnePass의 안전한 우편함(Outbox) 방식 (데이터 유실 제로)

OnePass는 업무 서류(DB 저장)와 알림 메모(게시판에 보낼 내용)를 **하나의 봉투에 넣고 동시에 처리(트랜잭션)**합니다. 그리고 우편 담당 직원(Relay 스케줄러)이 이 메모를 안전하게 게시판에 대신 전달합니다.

```mermaid
sequenceDiagram
    actor 사용자
    participant SYS as 회원 시스템 (Q-IM)
    participant DB as 데이터베이스<br/>(업무 DB + Outbox)
    participant RELAY as 우편 담당 직원<br/>(Relay 스케줄러)
    participant KAFKA as 사내 게시판<br/>(Kafka)
    participant IDO as 다른 시스템 (IdO)

    사용자->>SYS: 회원 정보 수정 요청

    rect rgb(224, 240, 255)
        note over SYS,DB: 🔒 한 봉투에 묶어서 처리 — 트랜잭션 (둘 다 성공 or 둘 다 취소)
        SYS->>DB: ① 업무 정보 수정 저장
        SYS->>DB: ② '보낼 편지함(Outbox)'에 알림 메모 저장
    end

    SYS-->>사용자: 수정 완료 안내 (즉시 응답)

    loop 0.5초마다 반복 확인
        RELAY->>DB: ③ 보낼 편지함에 새 메모 있는지 확인
        RELAY->>KAFKA: ④ 게시판에 메모 안전하게 전달 (재시도 포함)
        KAFKA-->>RELAY: ⑤ 전달 완료 확인
        RELAY->>DB: ⑥ 해당 메모 '발송 완료' 처리
        KAFKA-->>IDO: ⑦ 메모 수신 → 후속 처리 자동 실행
    end
```

> **핵심**: 사용자는 ①②가 끝나면 즉시 응답을 받습니다. 게시판 전달(③~⑦)은 백그라운드에서 자동 처리됩니다. 게시판이 일시 장애여도 메모는 DB에 안전하게 보관되므로 복구 즉시 자동 재발송됩니다.

---

## 3. 장애 상황에서도 안전한 이유 (무장애 보장)

담당 사무관님께서 안심하실 수 있는 3가지 핵심 방어막입니다.

### 🛡️ 방어막 1: 사내 게시판(Kafka) 서버가 다운된다면?

```mermaid
flowchart LR
    DB[(보낼 편지함\nOutbox DB)] -->|0.5초마다 확인| RELAY[우편 담당 직원\nRelay]
    RELAY -->|전달 시도| KAFKA{{사내 게시판\nKafka}}
    KAFKA -->|서버 다운💥| RELAY
    RELAY -->|실패 기록 후\n재시도 대기| DB

    style KAFKA fill:#ffcccc,stroke:#cc0000
    style DB fill:#e6f3ff,stroke:#0066cc
    style RELAY fill:#e6ffe6,stroke:#009900
```

게시판 서버가 다운되어도, 메모는 데이터베이스의 '보낼 편지함'에 안전하게 보관됩니다. 우편 담당 직원(Relay)은 게시판이 복구될 때까지 포기하지 않고 **자동으로 계속 재시도**합니다. 데이터는 절대 유실되지 않습니다.

### 🛡️ 방어막 2: 우편 담당 직원(Relay)이 실수로 두 번 보낸다면?

```mermaid
sequenceDiagram
    participant RELAY as 우편 담당 직원
    participant KAFKA as 게시판 (Kafka)
    participant IDO as IdO (수신자)
    participant STORE as 처리이력 저장소

    RELAY->>KAFKA: 메모 전달 (일련번호: EVT-2024-001)
    KAFKA-->>IDO: 메모 수신 ①
    IDO->>STORE: EVT-2024-001 처리 완료 기록

    note over RELAY,KAFKA: 네트워크 지연으로 같은 메모 재전달

    RELAY->>KAFKA: 동일 메모 재전달 (일련번호: EVT-2024-001)
    KAFKA-->>IDO: 메모 수신 ②
    IDO->>STORE: EVT-2024-001 이미 처리됨 확인
    IDO-->>KAFKA: 중복 메모 조용히 무시 (중복 처리 없음 ✅)
```

메모를 읽는 수신자 시스템(IdO)은 **'고유 일련번호(이벤트 ID)'**를 확인하여 이미 처리한 메모를 똑똑하게 무시합니다. (기술 용어: 멱등성 보장)

### 🛡️ 방어막 3: 메모 순서가 섞이면 어떡하나요?

```mermaid
sequenceDiagram
    participant KAFKA as 게시판 (Kafka)
    participant IDO as IdO (수신자)

    note over KAFKA: 같은 사용자(OOO)의 메모는<br/>항상 동일한 번호표(파티션) 배정

    KAFKA->>IDO: 메모 ① "OOO 회원 가입" (순서: 1번)
    IDO->>IDO: 처리 완료 (버전 1 기록)

    KAFKA->>IDO: 메모 ② "OOO 기업 전환" (순서: 2번)
    IDO->>IDO: 처리 완료 (버전 2 기록)

    KAFKA--xIDO: 메모 ③ "OOO 탈퇴" (순서: 1번?! — 오래된 메모 재전달)
    IDO->>IDO: 버전 확인 → 이미 버전 2 처리됨<br/>순서 역전 메모 자동 폐기 ✅
```

"회원 가입" 메모보다 "탈퇴" 메모가 먼저 처리되면 시스템이 꼬일 수 있습니다. OnePass는 같은 사용자에 대한 메모에는 반드시 **동일한 번호표(파티션 키)**를 부여하고, 이벤트 버전 번호로 순서를 이중 검증하여 정확하게 일렬로 처리합니다.

---

## 4. 각 시스템(팀)의 역할 요약

비즈니스 관점에서 각 시스템이 하는 일을 설명합니다.

### 4.1 Q-Sign (인증 담당) — 메모 작성자이자 수신자

사용자가 아이디/비밀번호, 소셜 로그인 등으로 **인증에 성공하거나 실패**했을 때, 그 결과를 게시판에 메모로 남깁니다.

| 상황 | 게시판에 남기는 메모 |
|------|---------------------|
| 인증 성공 | "OOO 사용자 인증이 완료되었습니다 (AUTH_COMPLETED)" |
| 인증 실패 | "OOO 사용자 인증이 실패했습니다 (AUTH_FAILED)" |
| 계정 잠금 | "OOO 사용자 계정이 잠겼습니다 (AUTH_LOCKED)" |

> **이 메모를 읽는 시스템**: IdO

또한 Q-Sign은 회원 시스템(Q-IM)이 남긴 **계정 정지·탈퇴 메모도 수신**하여, 해당 사용자의 신규 로그인 시도를 즉시 차단합니다.

---

### 4.2 Q-IM (회원정보 담당) — 메모 작성자

**회원 가입, 기업 전환, 계정 정지, 탈퇴** 등 회원 신상에 변화가 생겼을 때, 그 사실을 게시판에 메모로 남깁니다.

| 상황 | 게시판에 남기는 메모 |
|------|---------------------|
| 회원 가입 | "새 회원 OOO이 가입했습니다 (USER_REGISTERED)" |
| 기업 전환 | "OOO 사용자가 기업 회원으로 전환되었습니다 (BIZ_CONVERTED)" |
| 회원 정보 수정 | "OOO 사용자 정보가 변경되었습니다 (USER_UPDATED)" |
| 계정 정지 | "OOO 사용자 계정이 정지되었습니다 (USER_SUSPENDED)" |
| 회원 탈퇴 | "OOO 사용자가 탈퇴하였습니다 (USER_WITHDRAWN)" |

> **이 메모를 읽는 시스템**: IdO와 Q-Sign **둘 다** 이 게시판을 읽습니다.
> - **IdO**: 회원 가입·기업 전환 메모를 받으면 68개 기관에 회원 정보를 전달합니다. 나머지 메모는 내부 캐시를 최신 상태로 갱신합니다.
> - **Q-Sign(인증 시스템)**: 계정 정지·탈퇴 메모를 받으면 해당 사용자의 신규 인증 시도를 즉시 차단합니다.

---

### 4.3 IdO (기관 연동·조율 담당) — 메모 읽기 + 새 메모 작성

IdO는 가장 복잡한 역할을 맡습니다. **다른 시스템의 메모를 읽는 동시에, 자신도 새로운 메모를 작성**합니다.

#### ① 읽는 메모 (Consumer)

| 읽는 메모 출처 | 수신하는 상황 | IdO가 하는 일 |
|--------------|-------------|--------------|
| **Q-Sign** | 인증 성공 (AUTH_COMPLETED) | 해당 인증 결과를 고속 캐시(Redis)에 미리 저장 → 기관 이동(Handoff) 요청 즉시 처리 준비 |
| **Q-Sign** | 계정 잠금 (AUTH_LOCKED) | 해당 사용자의 웹 화면(FE) 세션을 즉시 강제 종료 |
| **Q-Sign** | 인증 실패 (AUTH_FAILED) | 실패 기록 저장 (감사 로그) |
| **Q-IM** | 회원 가입 (USER_REGISTERED) / 기업 전환 (BIZ_CONVERTED) | 연동된 68개 기관 모두에 회원 정보 전달 (프로비저닝) |
| **Q-IM** | 정보 수정 (USER_UPDATED) / 계정 정지 (USER_SUSPENDED) / 탈퇴 (USER_WITHDRAWN) | 내부 캐시 무효화 → 다음 요청 시 최신 정보 재조회 |
| **IdO 내부** | Handoff 티켓 발급됨 | 기관 서버에 "사용자가 이동 중입니다" 알림(Webhook) 발송 준비 |

#### ② 새로 작성하는 메모 (Producer)

IdO는 자신의 업무를 처리한 뒤, 그 결과를 다시 게시판에 남겨 감사 추적과 연계 처리를 가능하게 합니다.

| IdO가 작성하는 메모 | 게시판 | 이유 |
|--------------------|--------|------|
| Handoff 티켓 발급/소비/만료/취소 | `ido.handoff.events` | 기관 Webhook 트리거 + 감사 기록 |
| FE 세션 강제 종료 명령 | `platform.session.advisory` | FE가 즉시 해당 사용자 세션 무효화 |
| 플랫폼 전역 감사 로그 | `platform.audit.log` | 법적 감사 추적 (5년 보존) |

> **핵심 설명**: 기관들은 OnePass 내부 게시판(Kafka)에 직접 접속할 수 없습니다. 따라서 IdO가 게시판에서 메모를 읽은 후, **HTTPS 방식의 "알림 전화(Webhook)"**를 각 기관 서버에 직접 걸어 알려주는 방식으로 연동합니다. 이 기관 알림은 **Handoff 티켓 발급/취소** 상황에서만 발송됩니다. 계정 잠금(AUTH_LOCKED)은 기관 Webhook 없이 내부 FE 세션 처리로만 종결됩니다.

---

## 5. 전체 흐름 한 눈에 보기 (비즈니스 시나리오)

### 시나리오 A: 사용자가 로그인 후 기관 서비스로 이동하는 경우

```mermaid
sequenceDiagram
    actor 사용자
    participant QS as Q-Sign (인증)
    participant DB_QS as Q-Sign DB<br/>(Outbox 포함)
    participant RELAY_QS as Q-Sign Relay
    participant KAFKA as 게시판 (Kafka)
    participant IDO as IdO (중재자)
    participant 기관 as 유관기관 서버

    사용자->>QS: ① 로그인 (ID/PW 입력)

    rect rgb(224, 240, 255)
        note over QS,DB_QS: 🔒 트랜잭션 — 인증 처리 + Outbox 저장 원자적 처리
        QS->>DB_QS: ② 인증 성공 기록 저장
        QS->>DB_QS: ③ Outbox에 AUTH_COMPLETED 이벤트 저장
    end

    QS-->>사용자: ④ 로그인 성공 응답

    RELAY_QS->>DB_QS: ⑤ Outbox 확인 → 새 이벤트 발견
    RELAY_QS->>KAFKA: ⑥ AUTH_COMPLETED 이벤트 발행

    KAFKA-->>IDO: ⑦ 이벤트 수신
    IDO->>IDO: ⑧ 인증 결과를 Redis 캐시에 Pre-warming<br/>(Handoff 즉시 처리 준비)

    사용자->>IDO: ⑨ "기관 서비스로 이동" 요청 (HTTP API)
    IDO->>IDO: ⑩ Redis 캐시에서 인증 결과 즉시 확인<br/>→ DB 조회 없이 Handoff 티켓 발급

    rect rgb(224, 255, 224)
        note over IDO,KAFKA: 🔒 트랜잭션 — Handoff 저장 + Outbox 원자적 처리
        IDO->>KAFKA: ⑪ HANDOFF_ISSUED 이벤트 발행 (Outbox Relay 경유)
    end

    KAFKA-->>IDO: ⑫ 이벤트 수신 (HandoffEventConsumer)
    IDO->>기관: ⑬ "사용자 OOO이 곧 이동합니다" Webhook 발송<br/>(webhook_dispatch_outbox → Relay → HTTPS POST)
    IDO-->>사용자: ⑭ 기관 서비스 URL 반환 → 이동 완료
```

---

### 시나리오 B: 비밀번호 5회 오류로 계정이 잠기는 경우

계정 잠금은 **FE 세션 강제 종료**로 처리됩니다. 기관 Webhook 발송은 이루어지지 않습니다.

```mermaid
sequenceDiagram
    actor 사용자
    participant QS as Q-Sign (인증)
    participant DB_QS as Q-Sign DB<br/>(Outbox 포함)
    participant RELAY_QS as Q-Sign Relay
    participant KAFKA as 게시판 (Kafka)
    participant IDO as IdO (중재자)
    participant FE as OnePass FE (화면)

    사용자->>QS: ① 비밀번호 5회 연속 오류
    rect rgb(255, 240, 224)
        note over QS,DB_QS: 🔒 트랜잭션 — 잠금 처리 + Outbox 저장
        QS->>DB_QS: ② auth_lock 강제 잠금 기록
        QS->>DB_QS: ③ Outbox에 AUTH_LOCKED 이벤트 저장
    end
    QS-->>사용자: ④ 계정 잠금 안내 응답

    RELAY_QS->>DB_QS: ⑤ Outbox 확인 → AUTH_LOCKED 이벤트 발견
    RELAY_QS->>KAFKA: ⑥ AUTH_LOCKED 이벤트 발행

    KAFKA-->>IDO: ⑦ 이벤트 수신 (QsignAuthEventConsumer)
    IDO->>IDO: ⑧ 해당 사용자 인증 결과 캐시 즉시 무효화 (보안)
    IDO->>KAFKA: ⑨ MANDATORY_SECURITY_TERMINATE 발행<br/>(platform.session.advisory 토픽)

    KAFKA-->>FE: ⑩ Advisory 수신 (FeAdvisoryConsumer)
    FE->>FE: ⑪ 해당 사용자 FE 세션 즉시 일괄 무효화

    note over FE: ✅ 처리 완료<br/>기관 Webhook 발송 없음.<br/>계정 잠금은 FE 세션 처리로 종결.
```

---

### 시나리오 C: 신규 회원 가입 시 68개 기관 전파

```mermaid
sequenceDiagram
    actor 국민
    participant QIM as Q-IM (회원 시스템)
    participant DB_QIM as Q-IM DB<br/>(Outbox 포함)
    participant RELAY_QIM as Q-IM Relay
    participant KAFKA as 게시판 (Kafka)
    participant IDO as IdO (중재자)
    participant PROV_DB as IdO DB<br/>(provisioning_outbox)
    participant PROV_RELAY as 프로비저닝 Relay
    participant 기관들 as 68개 유관기관

    국민->>QIM: ① 회원 가입 요청

    rect rgb(224, 240, 255)
        note over QIM,DB_QIM: 🔒 트랜잭션 — 회원 저장 + Outbox 원자적 처리
        QIM->>DB_QIM: ② 회원 정보 DB 저장
        QIM->>DB_QIM: ③ Outbox에 USER_REGISTERED 이벤트 저장
    end
    QIM-->>국민: ④ 가입 완료 응답

    RELAY_QIM->>DB_QIM: ⑤ Outbox 확인 → USER_REGISTERED 발견
    RELAY_QIM->>KAFKA: ⑥ USER_REGISTERED 이벤트 발행

    KAFKA-->>IDO: ⑦ 이벤트 수신 (QimEventConsumer)
    IDO->>IDO: ⑧ 사용자 캐시 무효화 + Q-IM API Pull (최신 정보 조회)

    rect rgb(224, 255, 224)
        note over IDO,PROV_DB: 🔒 트랜잭션 — provisioning_outbox 일괄 INSERT (68개)
        IDO->>PROV_DB: ⑨ 68개 기관 각각에 대한 provisioning_outbox 레코드 저장
    end

    PROV_RELAY->>PROV_DB: ⑩ provisioning_outbox 확인
    PROV_RELAY->>기관들: ⑪ 68개 기관에 Virtual Thread 병렬 HTTP POST<br/>(장애 기관 → PENDING 상태 유지 → 자동 재시도)

    note over 기관들: ✅ 전 기관 회원 정보 동기화 완료<br/>장애 기관은 자동 재시도로 데이터 유실 없음
```

---

### 시나리오 D: 계정 정지 또는 탈퇴 시 인증 차단

계정 정지(USER_SUSPENDED)·탈퇴(USER_WITHDRAWN)는 **IdO와 Q-Sign이 각자의 역할로 동시에 처리**합니다.

```mermaid
sequenceDiagram
    participant QIM as Q-IM (회원 시스템)
    participant DB_QIM as Q-IM DB<br/>(Outbox 포함)
    participant RELAY_QIM as Q-IM Relay
    participant KAFKA as 게시판 (Kafka)
    participant IDO as IdO (중재자)
    participant QS as Q-Sign (인증)

    note over QIM: 관리자가 계정 정지 처리
    rect rgb(255, 240, 224)
        note over QIM,DB_QIM: 🔒 트랜잭션 — 상태 변경 + Outbox 저장
        QIM->>DB_QIM: ① 사용자 상태 SUSPENDED로 변경
        QIM->>DB_QIM: ② Outbox에 USER_SUSPENDED 이벤트 저장
    end

    RELAY_QIM->>DB_QIM: ③ Outbox 확인 → USER_SUSPENDED 발견
    RELAY_QIM->>KAFKA: ④ USER_SUSPENDED 이벤트 발행

    par IdO 처리 (ido-qim-consumer 그룹)
        KAFKA-->>IDO: ⑤ 이벤트 수신
        IDO->>IDO: ⑥ 사용자 상태 캐시 무효화<br/>Q-IM API Pull → 최신 상태 갱신
        note over IDO: 캐시 무효화 후 다음 Handoff 요청 시<br/>자동으로 SUSPENDED 상태 감지 → 발급 거부
    and Q-Sign 처리 (q-sign-qim-consumer 그룹)
        KAFKA-->>QS: ⑤ 이벤트 수신 (동일 이벤트, 독립 처리)
        QS->>QS: ⑥ auth_lock 강제 잠금 처리<br/>→ 해당 사용자 신규 로그인 시도 즉시 차단
        note over QS: 이후 해당 사용자의 로그인 시도 시<br/>auth_lock 잠금 확인 → 자동 거부
    end
```

> **왜 둘 다 처리하나요?** IdO의 캐시 무효화는 "Handoff 발급 시 최신 상태 확인"을 위한 것이고, Q-Sign의 auth_lock 잠금은 "로그인 자체를 차단"하는 것입니다. 두 시스템이 독립적으로 처리하기 때문에 어느 한쪽이 일시 장애여도 다른 쪽의 방어가 작동합니다.

---

## 6. 결론: "시스템은 각자의 전문 역할에만 집중합니다"

```mermaid
flowchart TB
    subgraph KAFKA_BUS["🗂️ 사내 게시판 (Kafka) — 메시지 버스"]
        direction LR
        T1[qsign.auth.events]
        T2[qim.user.events]
        T3[ido.handoff.events]
        T4[platform.session.advisory]
        T5[platform.audit.log]
    end

    QS["🔐 Q-Sign\n국민 인증"] -->|AUTH_COMPLETED\nAUTH_LOCKED\nAUTH_FAILED| T1
    QIM["👤 Q-IM\n회원 정보 관리"] -->|USER_REGISTERED\nUSER_SUSPENDED\nUSER_WITHDRAWN 등| T2

    T1 -->|읽기| IDO["⚙️ IdO\n기관 연동·조율\n(오케스트레이터)"]
    T2 -->|읽기| IDO
    T2 -->|읽기| QS

    IDO -->|HANDOFF_ISSUED 등| T3
    IDO -->|MANDATORY_TERMINATE| T4
    IDO -->|감사 기록| T5

    T3 -->|읽기| IDO
    T4 -->|읽기| FE["🖥️ OnePass FE\n화면 세션 관리"]

    IDO -->|Webhook\nHTTPS POST| 기관들["🏢 68개 유관기관\n(Kafka 직접 접속 불가)"]
```

| 시스템 | 핵심 역할 | Kafka에서 하는 일 |
|--------|----------|-----------------| 
| **Q-Sign** | 국민 인증 | 인증 결과 게시. 계정 정지·탈퇴 메모 수신 시 신규 인증 차단 |
| **Q-IM** | 회원 정보 관리 | 회원 변경 사실 게시 |
| **IdO** | 기관 연동·조율 | 메모 읽고 후속 조치 실행. 필요 시 새 메모 작성. 기관엔 Webhook으로 변환 전달 |
| **FE** | 화면 세션 관리 | Advisory 메모 수신 시 해당 사용자 세션 즉시 무효화 |

이러한 **안전한 우편함(Outbox) 체계**가 플랫폼 밑바탕에 완벽히 구축되어 있기 때문에, 각 시스템은 자신의 전문 영역에만 집중할 수 있습니다.

- Q-Sign 팀: "어떻게 사용자를 안전하게 인증할 것인가?"
- Q-IM 팀: "어떻게 회원 정보를 정확하게 관리할 것인가?"
- IdO 팀: "어떻게 68개 기관과 원활하게 연동할 것인가?"

OnePass 플랫폼은 대규모 트래픽(동시 6만 명)과 예상치 못한 인프라 장애 속에서도 굳건하게 데이터를 지켜내는 국가대표급 안정성을 자랑합니다.
