
# IdO 프로젝트 데이터베이스 사용 용도 추적 보고서

### 1. 개요 (Overview)

ido 모듈의 데이터베이스(스키마: ido)는 OnePass 통합인증 플랫폼의 정책 오케스트레이터(Policy Orchestrator) 역할을 수행하기 위한 핵심 상태 및 메타데이터를 저장하는 용도로 사용됩니다.

이 데이터베이스는 사용자 정보의 원본(Source of Record, SoR)이 아닙니다. 대신, 인증/식별 과정에서 발생하는 일시적인 상태, 보안 정책, 감사 추적, 시스템 신뢰성 확보를 위한 데이터를 관리하는 데 특화되어
있습니다. 주요 역할은 다음과 같습니다.

- 상태 관리: Handoff Ticket, FE 세션 등 짧은 생명주기를 가진 인증-권한 부여 상태를 관리합니다.
- 보안 정책 저장: 유관기관별 API 키, 콜백 URL 화이트리스트, 암호화 키 버전 등 보안 정책의 원본을 저장합니다.
- 감사 및 추적: 모든 인증 흐름, 세션 이벤트, 정책 변경에 대한 상세한 감사 로그를 기록하여 규제 준수 및 문제 해결을 지원합니다.
- 신뢰성/무결성 보장: Transactional Outbox, 멱등성 보장(Idempotent Consumer) 등 분산 시스템의 신뢰성을 높이는 패턴을 구현하기 위한 데이터를 저장합니다.

────────────────────────────────────────────────────────────────────────────────

### 2. 핵심 데이터 테이블 및 사용 용도 분석

ido 데이터베이스의 주요 테이블과 각 테이블의 역할을 비즈니스 로직과 연관 지어 분석한 결과는 다음과 같습니다.

#### 2.1. 기관 정책 및 보안 관리

- agency_meta
    - 목적: 68개 유관기관의 정책 및 보안 설정 원본을 관리합니다.
    - 주요 컬럼 및 역할:
        - agency_code: 기관을 식별하는 고유 코드 (Primary Key).
        - api_key_hash: 기관이 API 호출 시 사용하는 API 키의 PBKDF2 해시값. Open Redirect 및 위조 요청 방지의 핵심 요소.
        - callback_whitelist: Handoff 완료 후 리디렉션될 수 있는 URL 목록 (JSONB). 등록되지 않은 URL로의 리디렉션을 차단하여 Open Redirect 공격을 원천 방어합니다.
        - webhook_endpoint: Handoff 이벤트 등을 비동기적으로 통지할 기관의 Webhook 수신 URL.
    - 연관 비즈니스 로직:
        - 회원 전환 API (/api/v1/conversion/init): api_key_hash를 이용해 기관이 보낸 JWT 서명을 검증합니다.
        - Handoff 발급 API (/api/v1/handoff/issue): 요청에 포함된 returnUrl이 callback_whitelist에 있는지 검증합니다.

#### 2.2. Handoff 및 세션 상태 관리

- handoff_audit
    - 목적: Handoff Ticket의 전체 생명주기(발급, 사용, 만료, 폐기)를 감사하고 추적하기 위해 사용됩니다. Redis가 Ticket의 주 저장소이지만, 이 테이블은 영구적인 감사 기록을 남기는 역할을 합니다.
    - 주요 컬럼 및 역할:
        - ticket_id: Handoff Ticket의 고유 ID. Redis의 키와 동일합니다.
        - state: 티켓의 현재 상태 (ISSUED, CONSUMED, EXPIRED).
        - qim_user_id: 티켓에 할당된 사용자의 ID.
    - 연관 비즈니스 로직:
        - Handoff 발급 (HandoffService.issueTicket): 티켓 발급 시 ISSUED 상태로 로그를 기록합니다.
        - Handoff 검증 (HandoffService.verifyTicket): 기관이 티켓을 사용하면 CONSUMED 상태로 업데이트합니다.
- fe_session_audit
    - 목적: 프론트엔드(React) 사용자의 세션 생명주기(생성, 만료, 무효화)를 감사하기 위한 테이블입니다. handoff_audit과 마찬가지로 주 저장소는 Redis이며, 이 테이블은 보안 감사 및 사용자 활동 추적을
      위해 사용됩니다.
    - 주요 컬럼 및 역할:
        - fe_session_id: 브라우저 쿠키에 저장되는 세션 ID.
        - event_type: 세션 이벤트 유형 (CREATED, EXPIRED, INVALIDATED).
    - 연관 비즈니스 로직:
        - 로그인 성공 시 (FeSessionService.createSession): 로그인 완료 후 CREATED 이벤트를 기록합니다.
        - 로그아웃 (FeSessionService.invalidateSession): 사용자가 로그아웃하면 INVALIDATED 이벤트를 기록합니다.

#### 2.3. 시스템 신뢰성 및 무결성

- outbox (Transactional Outbox)
    - 목적: ido 서비스가 Kafka로 이벤트를 발행할 때, 데이터베이스 트랜잭션과 메시지 발행을 원자적으로 묶어 데이터 정합성을 보장합니다. (예: Handoff 발급 DB 저장과 idem.hub.handoff.events 토픽 발행을
      동시에 보장)
    - 주요 컬럼 및 역할:
        - status: 이벤트 발행 상태 (PENDING, PUBLISHED, FAILED).
        - payload: Kafka로 보낼 실제 메시지 내용.
    - 연관 비즈니스 로직:
        - HandoffService 등 주요 서비스는 비즈니스 로직 처리와 함께 outbox 테이블에 PENDING 상태의 이벤트를 삽입합니다.
        - 별도의 Relay 컴포넌트(OutboxRelay)가 주기적으로 PENDING 이벤트를 조회하여 Kafka로 발행하고, 성공 시 PUBLISHED로 상태를 변경합니다.
- webhook_dispatch_outbox
    - 목적: 위 outbox와 동일한 패턴을 외부 기관 Webhook 발송에 적용한 것입니다. 내부 Kafka 이벤트를 수신한 후, 이 테이블에 Webhook 발송 작업을 PENDING 상태로 저장하여 최소 1회 발송(at-least-once)을
      보장합니다.
    - 연관 비즈니스 로직:
        - Kafka 컨슈머(HandoffEventConsumer)가 idem.hub.handoff.events 토픽을 구독하고, 수신한 이벤트를 기반으로 webhook_dispatch_outbox에 발송 작업을 기록합니다.
        - WebhookDispatchOutboxRelay가 PENDING 작업을 조회하여 기관의 webhook_endpoint로 실제 HTTPS POST 요청을 보냅니다.
- processed_event (Idempotent Consumer)
    - 목적: Kafka 등 메시지 시스템의 at-least-once 특성으로 인해 발생할 수 있는 메시지 중복 처리를 방지합니다.
    - 연관 비즈니스 로직: 모든 Kafka 컨슈머는 메시지를 처리하기 전, processed_event 테이블에 해당 event_id가 있는지 확인합니다. 이미 존재하면 처리를 건너뛰고, 없다면 event_id를 기록한 후 비즈니스
      로직을 수행합니다.

#### 2.4. 보안 및 운영 지원

- crypto_key_registry
    - 목적: Handoff Ticket 등 민감 정보를 암호화하는 데 사용되는 AES 키의 버전 및 메타데이터를 관리합니다.
    - 주요 컬럼 및 역할:
        - key_version: 키 버전 (v1, v2).
        - active, current_flag: 현재 암호화에 사용하는 키(current_flag=TRUE)와, 과거 데이터 복호화를 위해 유효한 키들(active=TRUE)을 구분합니다.
    - 연관 비즈니스 로직:
        - HandoffKeyRotationScheduler가 주기적으로 새 키 버전을 생성하고, 이전 키를 비활성화하는 키 로테이션(Key Rotation)을 자동화합니다.
        - 암호화/복호화 서비스는 이 테이블을 참조하여 적절한 버전의 키를 사용합니다.
- audit_log
    - 목적: platform.audit.log Kafka 토픽으로 발행되는 모든 주요 감사 로그를 로컬 데이터베이스에 2년간 영구 보관하여 규제 요건을 준수하고, 빠른 검색 및 조회를 지원합니다.
    - 연관 비즈니스 로직: 감사 로그 생성 시 Kafka 발행과 동시에 이 테이블에 INSERT가 발생합니다. Kafka 발행에 실패할 경우, kafka_published=FALSE 상태를 기반으로 재발행을 시도합니다.

────────────────────────────────────────────────────────────────────────────────

### 3. 결론

ido 데이터베이스는 OnePass 통합인증 플랫폼의 중앙 조정자(Orchestrator)로서, 민첩하고 안전한 인증/인가 흐름을 지원하기 위한 핵심 상태 저장소의 역할을 담당합니다.

사용자 정보와 같은 핵심 마스터 데이터를 직접 소유하지 않음으로써 책임을 명확히 분리하고, 대신 보안 정책, 일시적 상태, 감사 로그, 분산 트랜잭션 지원에 집중합니다. 이를 통해 전체 시스템의 안정성,
보안성, 확장성을 높이는 데 결정적인 기여를 합니다.
