# OnePass Platform — 통합인증 플랫폼

Kafka 기반 이벤트 드리븐 아키텍처(EDA)로 구축된 통합인증 플랫폼 PoC입니다.
70여 개 유관기관 연계를 위한 **Transactional Outbox**, **Idempotent Consumer**, **Compacted Topic** 패턴을 적용합니다.

---

## 목차

1. [프로젝트 구조](#1-프로젝트-구조)
2. [기술 스택](#2-기술-스택)
3. [개발 환경 준비](#3-개발-환경-준비)
4. [인프라 실행](#4-인프라-실행)
5. [애플리케이션 빌드 및 실행](#5-애플리케이션-빌드-및-실행)
6. [서비스 포트 및 관리 UI](#6-서비스-포트-및-관리-ui)
7. [코드 작성 규칙](#7-코드-작성-규칙)
8. [테스트 작성 및 실행](#8-테스트-작성-및-실행)
9. [Kafka 토픽 설계](#9-kafka-토픽-설계)
10. [DB 마이그레이션 (Flyway)](#10-db-마이그레이션-flyway)
11. [브랜치 및 커밋 전략](#11-브랜치-및-커밋-전략)
12. [트러블슈팅](#12-트러블슈팅)

---

## 1. 프로젝트 구조

```
onepass-platform/          # Gradle 루트 프로젝트
├── platform-common/       # 공통 도메인 / 유틸리티 (실행 JAR 없음, 라이브러리 전용)
├── q-sign/                # Q-Sign 인증 서비스          (port 8081)
├── q-im/                  # Q-IM 사용자 관리 서비스      (port 8082)
├── ido/                   # IdO (Identity Orchestrator)  (port 8083)
├── onepass-fe/            # OnePass 프론트엔드 서비스    (port 8080)
├── agency-stub/           # 유관기관 연동 스텁           (port 8084)
├── infra/docker/          # 로컬 인프라 Docker Compose
└── gradle/
    └── libs.versions.toml # 버전 카탈로그 (단일 버전 관리)
```

각 모듈은 `com.onepass.<모듈명>` 패키지를 루트로 사용하며, 아키텍처 레이어는 다음을 따릅니다.

```
api/           ← REST 컨트롤러 (입출력 DTO 포함)
application/   ← 서비스 인터페이스 + 구현체
domain/        ← 도메인 엔티티 및 값 객체
infrastructure/← 리포지토리 구현체, 외부 시스템 어댑터
outbox/        ← Transactional Outbox 릴레이 (q-im, q-sign)
config/        ← 스프링 설정 클래스
```

---

## 2. 기술 스택

| 범주 | 기술 | 버전 |
|------|------|------|
| 런타임 | Java (LTS) | 21 |
| 프레임워크 | Spring Boot | 3.5.9 |
| 빌드 | Gradle (Kotlin DSL) | Wrapper 포함 |
| DB | PostgreSQL | 16 |
| 캐시 | Redis | 7.2 |
| 메시지 | Kafka (Confluent) | 7.6.1 |
| 마이그레이션 | Flyway | 11.8.0 |
| 회복 탄력성 | Resilience4j | 2.2.0 |
| 인증 | JJWT | 0.12.6 |
| 매핑 | MapStruct | 1.6.3 |
| 테스트 인프라 | Testcontainers | 1.20.4 |
| 코드 생성 | Lombok | BOM 관리 |

---

## 3. 개발 환경 준비

### 3.1 필수 설치 항목

| 항목 | 최소 버전 | 확인 명령 |
|------|-----------|----------|
| JDK | 21 | `java -version` |
| Docker Desktop | 4.x | `docker --version` |
| Docker Compose | v2 (CLI 내장) | `docker compose version` |
| IntelliJ IDEA | 2023.1+ | — |

> **JDK 21 권장 배포판**: Eclipse Temurin 21 (Adoptium) 또는 Amazon Corretto 21

### 3.2 IntelliJ IDEA 설정

1. **Gradle JVM**: `File > Settings > Build > Gradle > Gradle JVM` → JDK 21 선택
2. **Lombok 플러그인**: Marketplace에서 *Lombok* 설치 후 `Enable annotation processing` 활성화
3. **인코딩**: `Settings > Editor > File Encodings` → 전체 UTF-8 통일
4. **Import**: `File > Open` → 루트 `build.gradle.kts` 선택 (Open as Project)

---

## 4. 인프라 실행

모든 명령은 프로젝트 루트에서 실행합니다.

### 4.1 기본 인프라 가동

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

Kafka 토픽 초기화 컨테이너(`kafka-init`)가 자동으로 실행되어 필요한 토픽을 생성합니다.
별도로 `create-topics.sh`를 수동 실행할 필요가 없습니다.

### 4.2 상태 확인

```bash
docker compose -f infra/docker/docker-compose.yml ps
```

모든 컨테이너가 `healthy` 상태가 될 때까지 대기합니다 (최초 기동 시 약 1~2분 소요).

### 4.3 선택적 프로파일

```bash
# Schema Registry 포함 (Avro 스키마 관리)
docker compose -f infra/docker/docker-compose.yml --profile schema up -d

# pgAdmin 포함 (DB GUI)
docker compose -f infra/docker/docker-compose.yml --profile tools up -d

# 두 프로파일 모두
docker compose -f infra/docker/docker-compose.yml --profile schema --profile tools up -d
```

### 4.4 인프라 종료 및 데이터 초기화

```bash
# 컨테이너만 중지 (데이터 볼륨 유지)
docker compose -f infra/docker/docker-compose.yml down

# 데이터 볼륨까지 완전 삭제 (초기화)
docker compose -f infra/docker/docker-compose.yml down -v
```

### 4.5 DB 연결 정보

| 항목 | 값 |
|------|----|
| Host | `localhost:5432` |
| Database | `onepass` |
| Username | `onepass` |
| Password | `onepass` |

각 서비스는 독립 스키마를 사용합니다: `qsign`, `qim`, `ido`, `fe`, `agency`

---

## 5. 애플리케이션 빌드 및 실행

### 5.1 전체 빌드

```bash
./gradlew clean build -x test
```

### 5.2 특정 모듈만 빌드

```bash
./gradlew :q-im:build -x test
./gradlew :ido:build -x test
```

### 5.3 실행 순서

서비스 간 의존 관계가 있으므로 아래 순서로 기동합니다.

```
[1] q-sign      (port 8081) — 인증 SoR
[2] q-im        (port 8082) — 사용자 SoR
[3] ido         (port 8083) — 인증 오케스트레이터
[4] onepass-fe  (port 8080) — 프론트엔드 게이트웨이
[5] agency-stub (port 8084) — 유관기관 스텁 (선택)
```

```bash
# 각 모듈을 개별 터미널에서 실행
./gradlew :q-sign:bootRun
./gradlew :q-im:bootRun
./gradlew :ido:bootRun
./gradlew :onepass-fe:bootRun
./gradlew :agency-stub:bootRun
```

IntelliJ에서는 각 `*Application.java`의 main 메서드를 직접 실행해도 됩니다.

### 5.4 환경 변수 (로컬 기본값)

애플리케이션은 환경 변수가 없으면 아래 기본값을 사용하므로, 로컬에서는 추가 설정 없이 바로 실행됩니다.

| 변수 | 기본값 |
|------|--------|
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `onepass` |
| `DB_USERNAME` | `onepass` |
| `DB_PASSWORD` | `onepass` |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |
| `KAFKA_SERVERS` | `localhost:9092` |

---

## 6. 서비스 포트 및 관리 UI

### 애플리케이션

| 서비스 | 포트 | 비고 |
|--------|------|------|
| onepass-fe | 8080 | 프론트엔드 진입점 |
| q-sign | 8081 | 인증 서비스 |
| q-im | 8082 | 사용자 관리 |
| ido | 8083 | 인증 오케스트레이터 |
| agency-stub | 8084 | 유관기관 스텁 |

### 인프라 관리 UI

| 도구 | URL | 계정 |
|------|-----|------|
| Kafka UI | http://localhost:8090 | admin / admin |
| Redis Insight | http://localhost:5540 | — |
| pgAdmin | http://localhost:5050 | admin@onepass.local / admin |
| Schema Registry | http://localhost:8085/subjects | profile: schema |

### Actuator 엔드포인트

각 서비스는 `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`를 노출합니다.

```bash
curl http://localhost:8082/actuator/health
```

---

## 7. 코드 작성 규칙

### 7.1 공통 원칙

- **SoR(Source of Record) 원칙 준수**: 각 데이터의 원천 서비스 외부에서는 해당 데이터를 직접 수정하지 않습니다.
  - 사용자 정보 → q-im 만 변경 가능
  - 인증 결과 → q-sign 만 기록 가능
- **이벤트 발행은 반드시 Transactional Outbox를 경유**합니다. Kafka Producer를 서비스 레이어에서 직접 호출하지 마십시오.
- 모든 Kafka 컨슈머는 **멱등성**을 보장해야 합니다. 동일 이벤트가 재전달되어도 데이터가 오염되지 않도록 구현합니다.

### 7.2 패키지 및 클래스 작성

- 인터페이스와 구현체를 분리합니다: `UserService` (interface) + `UserServiceImpl` (class)
- DTO는 API 레이어에만 둡니다. 도메인 객체를 컨트롤러 응답에 그대로 노출하지 않습니다.
- 엔티티-DTO 변환은 **MapStruct**를 사용합니다. 수동 변환 코드를 작성하지 마십시오.
- `@Transactional`은 서비스 구현체 메서드에만 선언합니다. 컨트롤러에 선언하지 않습니다.

### 7.3 Kafka 관련

- **파티션 키 규칙**: 동일 사용자의 이벤트 순서 보장을 위해 `qimUserId`를 파티션 키로 사용합니다.
- `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`로 설정되어 있습니다. 신규 토픽은 반드시 `create-topics.sh`에 추가하고 리뷰를 받아야 합니다.
- DLQ 토픽은 본 토픽 이름에 `.dlq` 접미사를 붙입니다.
- 컨슈머 오류 처리: 재시도 후 DLQ로 라우팅하는 패턴을 따릅니다. 예외를 삼키고 넘어가지 않습니다.

### 7.4 예외 처리

- 비즈니스 예외는 `platform-common`의 공통 예외 클래스를 상속합니다.
- 컨트롤러 레이어에서 `@ExceptionHandler` 또는 `@ControllerAdvice`로 일괄 처리합니다.
- 스택 트레이스 전체를 API 응답에 노출하지 않습니다.

### 7.5 설정 관리

- 민감한 설정값(비밀번호, 시크릿)은 코드에 하드코딩하지 않습니다. 환경 변수를 사용합니다.
- `application.yml`에 새로운 커스텀 설정을 추가할 때는 `@ConfigurationProperties` 클래스를 만들어 바인딩합니다.

### 7.6 의존성 추가

신규 라이브러리를 추가할 때는 반드시 `gradle/libs.versions.toml`에 버전을 등록한 후 각 모듈의 `build.gradle.kts`에서 참조합니다.

```toml
# libs.versions.toml에 버전 추가
[versions]
some-lib = "1.2.3"

[libraries]
some-lib = { module = "com.example:some-lib", version.ref = "some-lib" }
```

```kotlin
// build.gradle.kts에서 참조
dependencies {
    implementation(libs.some.lib)
}
```

---

## 8. 테스트 작성 및 실행

### 8.1 테스트 실행

```bash
# 전체 테스트
./gradlew test

# 특정 모듈 테스트
./gradlew :q-im:test

# 특정 클래스만
./gradlew :q-im:test --tests "com.onepass.qim.*"

# 테스트 결과 재실행 (캐시 무시)
./gradlew :q-im:test --rerun-tasks
```

테스트 리포트: `{module}/build/reports/tests/test/index.html`

### 8.2 테스트 종류 및 규칙

**단위 테스트**
- 외부 의존성(DB, Kafka, Redis)을 Mockito로 모킹합니다.
- 서비스, 도메인 로직 검증에 집중합니다.
- 클래스명 접미사: `*Test`

**통합 테스트**
- **Testcontainers**를 사용하여 실제 PostgreSQL, Kafka 컨테이너를 띄웁니다. 인메모리 H2나 Embedded Kafka로 대체하지 않습니다.
- 클래스명 접미사: `*IntegrationTest`
- `@SpringBootTest` + Testcontainers 조합을 기본으로 합니다.

**Outbox / 이벤트 테스트**
- Outbox 릴레이가 실제로 Kafka에 메시지를 발행하는지 통합 테스트로 검증합니다.
- 멱등성 검증: 동일 이벤트를 2회 이상 전달해도 부작용이 없음을 테스트로 증명합니다.

### 8.3 테스트 작성 시 주의사항

- `@Transactional`을 테스트 클래스에 붙여 롤백에 의존하는 테스트는 지양합니다 (Kafka 메시지 발행 누락 등 사이드 이펙트 발생).
- 테스트 데이터는 `@BeforeEach`에서 명시적으로 세팅하고 `@AfterEach`에서 정리합니다.
- 타임아웃이 있는 비동기 검증은 `Awaitility`를 사용합니다.

---

## 9. Kafka 토픽 설계

토픽 초기화는 `docker compose up` 시 `kafka-init` 컨테이너가 자동 처리합니다.

| 토픽 | 파티션 | 정책 | 보존 | 파티션 키 | 용도 |
|------|--------|------|------|-----------|------|
| `qsign.auth.events` | 6 | delete | 1년 | identifierHash | Q-Sign 인증 결과 이벤트 |
| `qsign.auth.events.dlq` | 3 | delete | 7일 | — | Q-Sign DLQ |
| `qim.user.events` | 12 | **compact** | — | qimUserId | Q-IM 사용자 변경 이벤트 |
| `qim.user.snapshot` | 12 | **compact** | — | qimUserId | Q-IM 전체 상태 스냅샷 |
| `qim.user.events.dlq` | 6 | delete | 7일 | — | Q-IM DLQ |
| `ido.handoff.events` | 6 | delete | 1년 | correlationId | IdO Handoff 이벤트 |
| `ido.handoff.events.dlq` | 3 | delete | 7일 | — | IdO DLQ |
| `platform.session.advisory` | 6 | delete | 1일 | qimUserId | 세션 권고 / 강제 로그아웃 |
| `platform.session.advisory.dlq` | 3 | delete | 7일 | — | Advisory DLQ |
| `platform.audit.log` | 6 | delete | 2년 | — | 플랫폼 감사 로그 |

> `compact` 정책 토픽에 메시지를 발행할 때 **반드시 파티션 키를 지정**해야 합니다. 키 없이 발행하면 Compaction이 올바르게 동작하지 않습니다.

---

## 10. DB 마이그레이션 (Flyway)

각 모듈은 자체 PostgreSQL 스키마를 소유하며 Flyway로 독립 관리합니다.

### 마이그레이션 파일 위치

```
{module}/src/main/resources/db/migration/
└── V{version}__{description}.sql
    예) V1__create_users_table.sql
        V2__add_auth_mean_mapping.sql
```

### 규칙

- 파일명은 반드시 `V{숫자}__{설명}.sql` 형식을 따릅니다 (언더스코어 두 개).
- **한번 커밋된 마이그레이션 파일은 절대 수정하지 않습니다.** 변경이 필요하면 새 버전을 추가합니다.
- DDL 변경은 마이그레이션 파일로만 진행합니다. `ddl-auto: validate`로 설정되어 있어, 스키마 불일치 시 애플리케이션이 기동하지 않습니다.
- 로컬에서 마이그레이션 초기화가 필요하면 `docker compose down -v`로 볼륨을 삭제 후 재기동합니다.

---

## 11. 브랜치 및 커밋 전략

### 브랜치 네이밍

```
feature/{이슈번호}-{간단한-설명}    예) feature/42-add-handoff-expiry
fix/{이슈번호}-{간단한-설명}        예) fix/55-outbox-relay-npe
refactor/{설명}
```

### 커밋 메시지

```
<type>(<scope>): <요약>

<상세 설명 (선택)>
```

| type | 사용 시점 |
|------|----------|
| `feat` | 신규 기능 |
| `fix` | 버그 수정 |
| `refactor` | 리팩터링 (기능 변화 없음) |
| `test` | 테스트 추가/수정 |
| `chore` | 빌드, 의존성 등 설정 변경 |
| `docs` | 문서 변경 |

예시:
```
feat(q-im): add idempotent outbox relay with retry backoff

- OutboxServiceImpl에 max-retry 3회, backoff 1초 적용
- 실패한 레코드는 FAILED 상태로 마킹하여 DLQ 라우팅 준비
```

### PR 규칙

- PR은 단일 책임 원칙을 따릅니다. 연관 없는 변경은 분리합니다.
- 셀프 리뷰 후 PR을 오픈합니다.
- 빌드 및 테스트가 통과해야 머지할 수 있습니다.

---

## 12. 트러블슈팅

### Kafka 연결 실패

```bash
# 브로커 상태 확인
docker logs onepass-kafka --tail 50

# 토픽 목록 확인
docker exec onepass-kafka kafka-topics --bootstrap-server localhost:9092 --list
```

### PostgreSQL 스키마 없음 오류

```bash
# DB 초기화 SQL 수동 적용
docker exec -i onepass-postgres psql -U onepass -d onepass < infra/docker/init-db.sql
```

### Flyway 체크섬 불일치

기존 마이그레이션 파일을 수정한 경우 발생합니다. 절대 기존 파일을 수정하지 말고 새 버전 파일을 추가하십시오.
로컬 개발 중 초기화가 필요하면:

```bash
docker compose -f infra/docker/docker-compose.yml down -v
docker compose -f infra/docker/docker-compose.yml up -d
```

### Kafka-init 컨테이너 실패

`docker logs onepass-kafka-init`으로 원인을 확인합니다. 브로커가 healthy 상태가 되기 전에 종료된 경우 아래로 재실행합니다.

```bash
docker compose -f infra/docker/docker-compose.yml restart kafka-init
```

### Lombok 코드 생성 오류 (IntelliJ)

`File > Invalidate Caches > Invalidate and Restart` 후 재빌드합니다.
`Enable annotation processing`이 비활성화된 경우 `Settings > Build > Compiler > Annotation Processors`에서 활성화합니다.
