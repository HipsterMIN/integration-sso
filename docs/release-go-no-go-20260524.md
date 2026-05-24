# OnePass 전체 서비스 출시 GO/NO-GO 재판정

- 작성일: 2026-05-24
- 기준 브랜치: `feature/onepass-support`
- 기준 커밋: `ec1b993 feat(docker): SSO/IM 및 Support 독립 실행을 위한 Docker Compose 분리 파일 추가`
- 범위: SSO/IM, Q-Sign, Q-IM, IdO, Agency Stub, SDK, Agent, Outbox Relay, OnePass FE, OnePass Support, Docker Compose 조합

## 최종 판정

**NO-GO**

출시 차단 이슈가 복수 확인되었다. 특히 IdO 테스트 소스 컴파일 실패, Agency Stub E2E 실패, Q-IM 통합 테스트 실패, OnePass Agent 회귀 테스트 실패, Frontend 빌드 실패가 남아 있어 전체 서비스 출시 기준을 만족하지 못한다.

OnePass Support의 전용 PostgreSQL compose 구성은 보완 후 정상 확인되었지만, 전체 출시 판정을 GO로 바꾸기에는 다른 핵심 서비스의 실패가 명확하다.

## 실행 요약

| 영역 | 명령/검증 | 결과 | 판정 |
| --- | --- | --- | --- |
| 전체 Gradle 테스트 | `.\gradlew.bat test` | `agency-stub:test` 실패로 중단 | FAIL |
| 전체 Gradle 테스트 계속 실행 | `.\gradlew.bat test --continue` | 6개 task 실패 | FAIL |
| Q-Sign | `.\gradlew.bat :q-sign:test --no-daemon --max-workers=1` | stale binary 삭제 후 성공 | PASS |
| Q-IM | `.\gradlew.bat :q-im:test --no-daemon --max-workers=1` | 251 tests, 21 failed | FAIL |
| IdO | `:ido:compileTestJava` | `MemberLookupControllerTest.java` 컴파일 오류 8건 | FAIL |
| Agency Stub | `:agency-stub:test` | E2E 6건 실패, 기대 200/403 대신 503 | FAIL |
| OnePass Agent | `:onepass-agent:test` | 250 tests, 1 failed | FAIL |
| SDK | `:onepass-agency-sdk:test` | up-to-date, 이전 실행 성공 상태 | PASS |
| Outbox Relay Batch | `:outbox-relay-batch:test` | up-to-date, 이전 실행 성공 상태 | PASS |
| Platform Common | `:platform-common:test` | up-to-date, 이전 실행 성공 상태 | PASS |
| OnePass FE | `.\gradlew.bat :onepass-fe:build` | `sass@1.100.0` Node engine 불일치 | FAIL |
| Support DB compose | `docker compose -f compose.base.yml -f compose.support.yml up -d support-postgres` | 최초 네트워크 충돌 후 수정, DB healthy | PASS |
| Support 앱 DB 기동 | support jar + `localhost:5433` | `/actuator/health` UP | PASS |
| Compose config | support / sso-im / support-monitoring / full 조합 | config 통과 | PASS |

## 주요 차단 이슈

### 1. IdO 테스트 소스 컴파일 실패

- 파일: `ido/src/test/java/kr/go/smes/ido/memberlookup/MemberLookupControllerTest.java`
- 증상:
  - 156행 부근에서 메서드/블록 구조가 깨져 `<identifier> expected`
  - 407행 이후 클래스 닫힘 뒤 중복 코드가 남아 `class, interface, enum, or record expected`
- 영향:
  - `:ido:compileTestJava` 자체가 실패하므로 IdO 테스트 스위트가 실행되지 못한다.
  - 인증 오케스트레이션, handoff, member lookup 관련 출시 신뢰성을 확보할 수 없다.
- 판정:
  - 출시 차단.

### 2. Agency Stub E2E 실패

- 명령: `.\gradlew.bat test`, `.\gradlew.bat test --continue`
- 실패 클래스: `AgencyHandoffE2EIntegrationTest`
- 실패 요약:
  - APPROVED 정상 SSO 흐름: 기대 `200 OK`, 실제 `503 SERVICE_UNAVAILABLE`
  - 동일 ticketId 멱등 흐름: 기대 `200 OK`, 실제 `503 SERVICE_UNAVAILABLE`
  - REJECTED 분기: 기대 `403 FORBIDDEN`, 실제 `503 SERVICE_UNAVAILABLE`
  - GUEST 분기: 기대 `200 OK`, 실제 `503 SERVICE_UNAVAILABLE`
  - 세션 lifecycle: AGSID 미발급
- 추가 로그:
  - Kafka consumer가 `localhost:19092`에 연결하지 못함
  - `Bootstrap broker localhost:19092 disconnected`
- 영향:
  - 기관 진입 → IdO verify → Q-Sign/IdO handoff → Agency 세션 발급 E2E가 깨져 있다.
- 판정:
  - 출시 차단.

### 3. Q-IM 테스트 실패

- 명령: `.\gradlew.bat :q-im:test --no-daemon --max-workers=1`
- 결과: `251 tests completed, 21 failed`
- 주요 실패:
  - `QimLifecycleIntegrationTest`: 17건 실패
    - ApplicationContext 로드 실패
    - 핵심 원인: 의존 Bean 누락으로 보이는 `NoSuchBeanDefinitionException`
  - `OutboxIntegrationTest`: 4건 실패
    - `markPublished()` 후 기대 `PUBLISHED`, 실제 `PENDING`
    - `markFailed()` 후 기대 `FAILED`, 실제 `PENDING`
    - retry count 기대값 불일치
    - 중복 eventId 예외 기대했으나 예외 미발생
- 영향:
  - Q-IM 회원 생명주기, 보호자 동의, 기업회원 전환, 탈퇴, Outbox 상태 전이가 검증되지 않는다.
- 판정:
  - 출시 차단.

### 4. OnePass Agent 회귀 테스트 실패

- 명령: `.\gradlew.bat test --continue`
- 결과: `250 tests completed, 1 failed`
- 실패:
  - `WeavingStrategyFactoryJeusTest`
  - 기대: `TomcatWeavingStrategy`
  - 실제: `TomcatVersionedWeavingStrategy`
- 영향:
  - WAS 자동 연동 agent의 Tomcat 라우팅 회귀 검증이 실패한다.
- 판정:
  - 출시 차단.

### 5. Frontend 빌드 실패

- 명령: `.\gradlew.bat :onepass-fe:build --no-daemon --max-workers=1`
- 실패 위치: `:onepass-fe:yarnInstall`
- 원인:
  - Gradle Node plugin 고정 Node 버전: `20.14.0`
  - 설치 대상 `sass@1.100.0` 요구 Node: `>=20.19.0`
  - 오류: `The engine "node" is incompatible with this module`
- 영향:
  - FE production build 산출물을 만들 수 없다.
- 판정:
  - 출시 차단.

## OnePass Support 확인 결과

### DB/Compose 구성

처음 `compose.support.yml` 실행 시 다음 문제가 있었다.

```text
failed to create network onepass-net: Pool overlaps with other one on this address space
```

원인:

- 기존 전체 compose가 실제로 만든 네트워크 이름은 `docker_onepass-net`
- 신규 `compose.base.yml`은 `onepass-net`이라는 새 네트워크 이름을 고정
- 같은 `172.20.0.0/24` 대역을 새 네트워크로 만들려다 충돌

조치:

- `compose.base.yml`의 네트워크 실제 이름을 `docker_onepass-net`으로 수정

확인:

```text
onepass-support-postgres   Up (healthy)   0.0.0.0:5433->5432/tcp
/var/run/postgresql:5432 - accepting connections
```

DB 테이블 확인:

```text
support.faq
support.faq_group
support.flyway_schema_history
support.qna_answer
support.qna_post
support.support_audit_log
support.support_phone_consultation
support.support_ticket
support.support_ticket_event
```

### 앱 기동

검증:

- `.\gradlew.bat :onepass-support:bootJar --no-daemon --max-workers=1 -x test`
- support jar 실행
- 환경변수:
  - `SUPPORT_DB_HOST=localhost`
  - `SUPPORT_DB_PORT=5433`
  - `SUPPORT_DB_NAME=onepass_support`
  - `SUPPORT_DB_USERNAME=onepass_support`
  - `SUPPORT_DB_PASSWORD=onepass_support`
- `/actuator/health` 확인

결과:

```json
{"status":"UP","groups":["liveness","readiness"]}
```

판정:

- Support 전용 PostgreSQL compose 구성과 앱 DB 연결은 PASS.
- 다만 전체 출시 판정은 다른 서비스 차단 이슈 때문에 NO-GO.

## Compose 검증 결과

다음 조합은 `docker compose config`를 통과했다.

```powershell
docker compose -f infra/docker/compose.base.yml -f infra/docker/compose.support.yml config
docker compose -f infra/docker/compose.base.yml -f infra/docker/compose.sso-im.yml config
docker compose -f infra/docker/compose.base.yml -f infra/docker/compose.support.yml -f infra/docker/compose.monitoring.yml --profile support-exporters config
docker compose -f infra/docker/compose.base.yml -f infra/docker/compose.sso-im.yml -f infra/docker/compose.sso-im-apps.yml -f infra/docker/compose.support.yml --profile app --profile keycloak --profile support-app config
```

주의:

- Docker CLI가 `C:\Users\User\.docker\config.json` 접근 권한 경고를 냈지만 compose 해석은 성공했다.
- 전체 compose 조합 검증 시 앱 secret은 임시 더미 값으로 주입했다.

## 권고 조치

출시 전 최소 조치:

1. `MemberLookupControllerTest.java` 문법 오류를 수정해 `:ido:compileTestJava`를 통과시킨다.
2. Agency Stub E2E의 `503 SERVICE_UNAVAILABLE` 원인을 추적한다.
   - Kafka `localhost:19092` 설정과 Testcontainers/compose broker 포트 설정을 우선 확인한다.
3. Q-IM `QimLifecycleIntegrationTest` ApplicationContext 빈 누락을 해결한다.
4. Q-IM Outbox 상태 전이/멱등성 테스트 기대값과 구현을 재정렬한다.
5. OnePass Agent의 Tomcat weaving strategy 기대값을 정책 기준으로 확정한 뒤 테스트 또는 구현을 수정한다.
6. OnePass FE Node 버전을 `20.19.0` 이상으로 올리거나 `sass` 버전을 고정해 빌드 가능 상태로 만든다.
7. 위 수정 후 다음 명령을 다시 통과시킨다.

```powershell
.\gradlew.bat test --continue
.\gradlew.bat :onepass-fe:build
docker compose -f infra/docker/compose.base.yml -f infra/docker/compose.sso-im.yml -f infra/docker/compose.sso-im-apps.yml -f infra/docker/compose.support.yml --profile app --profile keycloak --profile support-app config
```

## 결론

현재 기준 전체 서비스 출시는 **NO-GO**다.

Support 전용 PostgreSQL 분리는 정상 동작 확인까지 도달했지만, IdO, Agency E2E, Q-IM, Agent, FE에서 출시 차단 실패가 확인되었다. 이 상태로는 통합 인증 플랫폼 전체 기능의 회귀 안정성을 보장할 수 없으므로 main 병합 또는 운영 출시를 진행하지 않는 것이 맞다.
