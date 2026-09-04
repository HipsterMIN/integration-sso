# Idem 개명 대응표 (Naming)

> **확정일** 2026-09-04 · **제품명** OnePass → **Idem**(아이뎀) · **상태** 2·3단계 완료, 4·5단계 예정

## 1. 이름의 뜻과 원칙

- **Idem**: 라틴어 "같은 것". 이 플랫폼의 핵심인 **동일인 식별**(여러 기관의 계정을 한 사람으로 묶음)을 한 단어로 표현한다. ID + EM으로도 읽힌다.
- 포지셔닝: "범용 SSO"가 아니라 **어떤 IdP 위에든 얹는 회원통합·연합인가 계층(Federation Layer)**.
- 원칙: 제품명 하나 + **역할 기반 모듈명**. 모듈명에 고객(smes), 국가(kr), 암호 기술(q-)을 담지 않는다.
- 코어/에디션 분리: 한국 공공 종속 요소(CI/DI, NICE/OACX, AnyID)는 **Idem KR Public Edition** 으로 묶는다. OnePass·중기원패스는 첫 적용 사례(고객 서비스명)로만 남긴다.

## 2. 대응표

### 2.1 모듈 디렉터리 · Gradle 프로젝트 (3단계, 완료)

| 구명 | 신명 | 역할 |
|---|---|---|
| `platform-common` | `idem-common` | 공통 라이브러리 |
| `q-sign` | `idem-gate` | 인증 관문 — 로그인 프론트, OIDC 파사드(Keycloak 프록시), PKCE |
| `q-im` | `idem-registry` | 회원 원장 — 골든 레코드, 가명 ID(DI), 탈퇴 |
| `ido` | `idem-hub` | 오케스트레이션 — 세션 핸드오프, 프로비저닝, 웹훅, KMS, 본인인증 브로커 |
| `q-authz` | `idem-authz` | 연합 인가 — 역할 원장, SCIM, 만료·회수 전파 |
| `outbox-relay-batch` | `idem-relay` | Transactional Outbox 릴레이 배치 |
| `onepass-fe` | `idem-console` | 관리·사용자 웹 (React SPA) |
| `onepass-agency-sdk` | `idem-sdk-java` | 테넌트(기관)측 Java SDK |
| `onepass-agent` | `idem-agent` | 레거시 WAS용 Java Agent |
| `agency-stub` | `idem-tenant-sample` | 참조 테넌트 앱 |
| `onepass-agent-testbed` | `idem-agent-testbed` | 에이전트 테스트베드 (settings 미포함) |
| 루트 프로젝트 `onepass-platform` | `idem` | |

Gradle 태스크 경로는 그대로 따라간다 (`:ido:bootJar` → `:idem-hub:bootJar`). 산출물 파일명도 프로젝트명을 따르므로 `ido-0.1.0-SNAPSHOT.jar` → `idem-hub-0.1.0-SNAPSHOT.jar`, `onepass-agent-…-all.jar` → `idem-agent-…-all.jar`.

### 2.2 빌드·배포 산출물 (3단계, 완료)

| 항목 | 구명 | 신명 |
|---|---|---|
| Docker 이미지 (GHCR, compose) | `onepass-ido`, `onepass-qsign`, `onepass-qim`, `onepass-q-authz`, `onepass-batch`, `onepass-agency-stub`, `onepass-react` | `idem-hub`, `idem-gate`, `idem-registry`, `idem-authz`, `idem-relay`, `idem-tenant-sample`, `idem-console` |
| Helm 차트 | `infra/helm/onepass`, `infra/helm/ido` | `infra/helm/idem`, `infra/helm/idem-hub` |
| compose 네트워크·컨테이너 접두 | `onepass-*` | `idem-*` |
| Grafana 대시보드 파일 | `onepass-overview.json` | `idem-overview.json` |
| CI 워크플로 매트릭스·아티팩트 경로 | 구 디렉터리 기준 | 신 디렉터리 기준 |

### 2.3 용어

| 구 | 신 | 비고 |
|---|---|---|
| 기관 (agency) | 테넌트 (tenant) | 4단계에서 코드·API·DB로 확장. 기관 고유 속성은 KR 에디션 확장으로 분리 |
| OnePass / 중기원패스 | (제품명 아님) | 첫 적용 사례의 서비스명. 문서에서 사례로만 언급 |

## 3. 아직 바꾸지 않은 것 (4·5단계)

아래는 **런타임 식별자**라 코드·설정·데이터를 함께 옮겨야 하므로 이번 개명에서 의도적으로 제외했다. 새 코드도 4단계 전까지는 기존 값을 따른다.

| 구분 | 현재 값 | 비고 |
|---|---|---|
| Java 패키지 | `kr.go.smes.{qsign,qim,ido,authz,agency,batch,sdk,agent,common}` | 612개 파일. Kafka 이벤트·아웃박스 페이로드에 클래스명 직렬화 여부 사전 점검 필요 |
| `spring.application.name` | `q-sign`, `q-im`, `ido`, `q-authz`, `agency-stub` | Micrometer `application` 태그, Kafka client/consumer-group 접두와 연동 |
| 서비스 간 호출자 ID | `X-Source-System: q-sign`, `X-Outbound-Source: onepass-ido`, `ido.internal.api-keys.q-sign` 등 | 설정 키와 Java 상수가 짝을 이룸 |
| Redis 키 접두 | `ido:ticket:*`, `ido:rl:*`, `ido:idempotency:*` | 운영 데이터 |
| 환경변수 접두 | `IDO_*`, `QIM_*`, `ONEPASS_*`, `KEYCLOAK_*` | Helm/k8s secret 템플릿과 연동 |
| Keycloak | realm `onepass`, client `q-sign-client`, `ido-client` | realm export 포함 |
| DB | PostgreSQL `o`, MariaDB `qim`, 스키마·테이블명, Flyway 이력 | 5단계. 운영 데이터 없는 지금이 적기 |
| k8s 런타임 이름 | Service `ido-service`, `q-im-service`, ConfigMap `ido-config`, Vault role `ido-service`, namespace `smes` | 배포 환경과 함께 전환 |
| Prometheus job / 알림 라벨 | `job="ido"`, `job="q-sign"` | 대시보드·알림 규칙과 동시 변경 |
| FE 소스 | `idem-console/frontend/**` (패키지명 `onepass`, 에셋 `assets/onepass`, 외부 호스트) | FE 개명은 별도 작업 |
| Java 소스 내 주석·문자열 | `onepass-fe`, `q-sign` 등 | 4단계 패키지 이동 시 일괄 |
| Helm values 키 | `qsign:`, `qim:`, `ido:`, `batch:`, `agencyStub:` | 차트명·이미지명만 개명. 키 개명은 helm lint 가능한 환경에서 |
| 기존 문서 본문 | `docs/`, `wiki/` 의 Q-Sign·IdO·OnePass 표기 | 작성 시점 기록으로 유지. 경로 참조만 갱신 |

## 4. 단계

1. ~~이름 확정·가용성 확인~~ (Idem 확정. 상표(KIPRIS)·도메인·GitHub org·Maven 그룹 확인은 소유 주체가 수행)
2. ~~저장소·루트 프로젝트·문서 표제~~
3. ~~모듈 디렉터리·Gradle·Dockerfile·CI·Helm·이미지명~~
4. Java 패키지 이동 + 런타임 식별자(설정 키·헤더 값·Redis 접두·환경변수·k8s 이름·Prometheus 라벨) + agency→tenant 용어
5. DB명·스키마명·Keycloak realm
