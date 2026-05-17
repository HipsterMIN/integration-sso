# OnePass Agent 멀티 WAS 테스트베드

> **목적**: 유관기관 운영 배포 전 `onepass-agent`를 다양한 WAS/JDK 조합에서 안전하게 검증하는 독립 Docker Compose 환경

---

## 목차

1. [개요](#개요)
2. [아키텍처](#아키텍처)
3. [사전 조건](#사전-조건)
4. [빠른 시작 (Quick Start)](#빠른-시작)
5. [WAS별 환경 상세](#was별-환경-상세)
6. [Mock OnePass 서버](#mock-onepass-서버)
7. [테스트 자동화](#테스트-자동화)
8. [Agent 교체 및 반복 테스트](#agent-교체-및-반복-테스트)
9. [설정 참조](#설정-참조)
10. [트러블슈팅](#트러블슈팅)

---

## 개요

OnePass Agent(`onepass-agent-xxx-all.jar`)를 운영 서버에 배포하기 전,  
이 테스트베드에서 **위빙 성공 여부**, **SSO 인증 흐름**, **Fail-Open 동작**을 검증합니다.

```
┌──────────────────────────────────────────────────────────┐
│                  onepass-agent-testbed                    │
│                  (172.20.0.0/24 내부망)                   │
│                                                           │
│  ┌──────────────────┐   토큰검증    ┌─────────────────┐  │
│  │  tomcat-9 :18084 │──────────────▶│ mock-onepass    │  │
│  │  tomcat-10:18085 │              │ :18080          │  │
│  │  tomcat-8 :18083 │              │ /api/agent/     │  │
│  │  wildfly  :18086 │              │  verify         │  │
│  │  jetty    :18087 │              └─────────────────┘  │
│  │  springboot:18088│                                    │
│  │  unknown  :18089 │                                    │
│  └──────────────────┘                                    │
└──────────────────────────────────────────────────────────┘
```

| 서비스 | JDK | Servlet | 네임스페이스 | 주목적 |
|--------|-----|---------|-------------|--------|
| `tomcat-8` | JDK 8 | 3.1 | `javax` | 최소 JDK 환경 |
| `tomcat-9` | JDK 11 | 4.0 | `javax` | 공공기관 표준 환경 |
| `tomcat-10` | JDK 17 | 6.0 | `jakarta` | Jakarta EE 전환 검증 |
| `wildfly` | JDK 17 | 6.0 | `jakarta` | JBoss 계열 검증 |
| `jetty` | JDK 11 | 5.0 | `jakarta` | Jetty 위빙 검증 |
| `springboot-embedded` | JDK 17 | 6.0 | `jakarta` | Embedded WAS 감지 |
| `unknown-fallback` | JDK 11 | — | — | GenericFilter Fallback |
| `mock-onepass-server` | JDK 11 | — | — | 가짜 SSO API |

---

## 아키텍처

```
onepass-agent-testbed/
├── docker/
│   ├── docker-compose.yml          # 전체 서비스 정의
│   ├── Dockerfile.tomcat8          # Tomcat 8.5 + JDK 8
│   ├── Dockerfile.tomcat9          # Tomcat 9.0 + JDK 11
│   ├── Dockerfile.tomcat10         # Tomcat 10.1 + JDK 17 (jakarta)
│   ├── Dockerfile.wildfly          # WildFly 27 + JDK 17
│   ├── Dockerfile.jetty            # Jetty 11 + JDK 11
│   ├── Dockerfile.springboot       # Spring Boot 3 Embedded
│   └── Dockerfile.undertow         # Unknown/Fallback 검증
├── apps/
│   ├── mock-onepass-server/        # 가짜 OnePass SSO 서버
│   │   ├── Dockerfile
│   │   └── src/.../MockOnePassServer.java
│   └── sample-webapp/              # 테스트용 서블릿 앱
│       ├── Dockerfile
│       ├── build.xml
│       └── src/
│           ├── .../servlet/HealthServlet.java
│           ├── .../servlet/ProtectedServlet.java
│           └── .../servlet/PublicServlet.java
├── scripts/
│   ├── run-all-tests.sh            # 자동화 검증 스크립트
│   └── replace-agent.sh            # Agent JAR 교체 헬퍼
├── config/
│   └── onepass-agent.properties    # Agent 설정 템플릿
└── agent/                          # Agent JAR 배치 위치 (gitignore)
    └── onepass-agent-current.jar   # 현재 사용 중인 Agent
```

---

## 사전 조건

| 도구 | 최소 버전 | 확인 방법 |
|------|----------|----------|
| Docker | 20.10+ | `docker --version` |
| Docker Compose | 2.0+ | `docker compose version` |
| curl | 7.x+ | `curl --version` |

### Agent JAR 준비

```bash
# 1. onepass-agent 빌드
cd /path/to/integration-sso
./gradlew :onepass-agent:agentJar

# 2. 빌드 결과 확인
ls -lh onepass-agent/build/libs/onepass-agent-*-all.jar

# 3. testbed agent 디렉토리에 배치
mkdir -p onepass-agent-testbed/agent
cp onepass-agent/build/libs/onepass-agent-*-all.jar onepass-agent-testbed/agent/

# 4. 심볼릭 링크 설정 (replace-agent.sh가 자동 수행)
cd onepass-agent-testbed/agent
ln -sf onepass-agent-xxx-all.jar onepass-agent-current.jar
```

---

## 빠른 시작

### 1. 설정 파일 준비

```bash
cd onepass-agent-testbed

# Agent 설정 복사 (필요시 수정)
cp config/onepass-agent.properties config/onepass-agent.properties
# → onepass.server.url은 docker-compose.yml에서 mock-onepass-server로 자동 설정
```

### 2. 전체 환경 기동

```bash
cd docker
docker compose up -d

# 기동 상태 확인
docker compose ps

# 로그 스트리밍 (Ctrl+C로 종료)
docker compose logs -f
```

### 3. 특정 WAS만 기동

```bash
# Tomcat 9만 기동 (Mock 서버 포함)
docker compose up -d mock-onepass-server tomcat-9

# Jakarta 계열만 기동
docker compose up -d mock-onepass-server tomcat-10 wildfly jetty
```

### 4. 수동 검증

```bash
# Mock 서버 헬스체크
curl http://localhost:18080/health

# Tomcat 9: 인증 없는 요청 → 401
curl -v http://localhost:18084/sample-webapp/protected

# Tomcat 9: 유효 토큰 → 200
curl -H "Authorization: Bearer test-token-001" \
     http://localhost:18084/sample-webapp/protected

# Tomcat 9: 무효 토큰 → 401
curl -H "Authorization: Bearer invalid-token" \
     http://localhost:18084/sample-webapp/protected

# 공개 엔드포인트 (인증 없이 200)
curl http://localhost:18084/sample-webapp/public/

# 헬스체크 (인증 없이 200)
curl http://localhost:18084/sample-webapp/health
```

### 5. 자동화 테스트

```bash
cd onepass-agent-testbed
./scripts/run-all-tests.sh

# 특정 WAS만
./scripts/run-all-tests.sh --only=tomcat-9,tomcat-10

# WAS 유지하며 테스트
./scripts/run-all-tests.sh --keep-running --no-rebuild
```

---

## WAS별 환경 상세

### Tomcat 8.5 (포트 18083)

| 항목 | 값 |
|------|----|
| 이미지 | `eclipse-temurin:8u392-b08-jre-jammy` |
| Tomcat | 8.5.98 |
| Servlet | 3.1 (`javax`) |
| WasType | `TOMCAT_8` |
| 위빙 전략 | `TomcatVersionedWeavingStrategy` (byte-buddy Catalina Valve) |

```bash
# 로그 확인
docker compose logs -f tomcat-8

# 컨테이너 접속
docker compose exec tomcat-8 bash
```

### Tomcat 9 (포트 18084)

| 항목 | 값 |
|------|----|
| 이미지 | `eclipse-temurin:11-jre-jammy` |
| Tomcat | 9.0.85 |
| Servlet | 4.0 (`javax`) — Tomcat 9의 마지막 javax |
| WasType | `TOMCAT_9` |
| 위빙 전략 | `TomcatVersionedWeavingStrategy` (Catalina Valve + javax Filter) |
| 감지 단서 | `javax.servlet.http.HttpServletMapping` 존재 (Servlet 4.0 고유) |

### Tomcat 10 (포트 18085)

| 항목 | 값 |
|------|----|
| 이미지 | `eclipse-temurin:17-jre-jammy` |
| Tomcat | 10.1.18 |
| Servlet | 6.0 (`jakarta`) |
| WasType | `TOMCAT_10_PLUS` |
| 위빙 전략 | `TomcatVersionedWeavingStrategy` (jakarta.servlet.Filter 전용) |
| 감지 단서 | `jakarta.servlet.Filter` 존재 + `javax.servlet.Filter` 부재 |

> ⚠️ Tomcat 10+에서는 `javax.servlet.*`이 완전히 제거됩니다.  
> 기존 javax 기반 앱을 배포하려면 [Migration Tool](https://tomcat.apache.org/migration-10.html) 사용 필요.

### WildFly 27 (포트 18086)

| 항목 | 값 |
|------|----|
| 이미지 | `quay.io/wildfly/wildfly:27.0.1.Final-jdk17` |
| Servlet | 6.0 (`jakarta`) |
| WasType | `WILDFLY` |
| 위빙 전략 | `GenericFilterWeavingStrategy` (jakarta.servlet.Filter) |
| 관리 콘솔 | http://localhost:19990 (admin/admin) |

### Jetty 11 (포트 18087)

| 항목 | 값 |
|------|----|
| 이미지 | `eclipse-temurin:11-jre-jammy` |
| Jetty | 11.0.20 |
| Servlet | 5.0 (`jakarta`) |
| WasType | `JETTY_JAKARTA` |
| 위빙 전략 | `GenericFilterWeavingStrategy` (jakarta.servlet.Filter) |

### Spring Boot Embedded (포트 18088)

| 항목 | 값 |
|------|----|
| 이미지 | `eclipse-temurin:17-jre-jammy` |
| WasType | `TOMCAT_10_PLUS` (Embedded Tomcat 감지) |
| 특이사항 | `CATALINA_HOME` 없음 → WasDetector 클래스패스 감지 경로 |

### Unknown/Fallback (포트 18089)

| 항목 | 값 |
|------|----|
| 강제 WasType | `-Donepass.was.type=UNKNOWN` |
| 위빙 전략 | `GenericFilterWeavingStrategy` (javax + jakarta 이중) |
| 목적 | WasDetector 미감지 WAS의 Fallback 동작 검증 |

---

## Mock OnePass 서버

`mock-onepass-server`는 운영 OnePass SSO 서버를 대체하는 **순수 JDK HTTP 서버**입니다.

### 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/health` | 헬스체크 |
| `POST` | `/api/agent/verify` | Agent 토큰 검증 (주 호출 경로) |
| `POST` | `/api/v1/token/verify` | 대체 검증 경로 |
| `GET/POST` | `/admin/tokens` | 유효 토큰 목록 조회/추가 |
| `DELETE` | `/admin/tokens/{token}` | 토큰 제거 |
| `GET` | `/admin/stats` | 호출 통계 |
| `POST` | `/admin/simulate` | 오류/지연 시뮬레이션 |

### 유효 토큰 관리

```bash
MOCK_URL=http://localhost:18080

# 현재 유효 토큰 목록 조회
curl $MOCK_URL/admin/tokens

# 새 토큰 추가
curl -X POST $MOCK_URL/admin/tokens \
     -H "Content-Type: application/json" \
     -d '{"token":"my-new-token","userId":"user123","roles":["USER","ADMIN"]}'

# 토큰 제거
curl -X DELETE $MOCK_URL/admin/tokens/my-new-token

# 통계 확인
curl $MOCK_URL/admin/stats
```

### 장애 시뮬레이션

```bash
# 5초 응답 지연 시뮬레이션 (Fail-Open 검증)
curl -X POST $MOCK_URL/admin/simulate \
     -H "Content-Type: application/json" \
     -d '{"delayMs":5000,"durationSeconds":30}'

# 500 오류 응답 시뮬레이션
curl -X POST $MOCK_URL/admin/simulate \
     -H "Content-Type: application/json" \
     -d '{"errorCode":500,"durationSeconds":30}'

# 시뮬레이션 초기화
curl -X POST $MOCK_URL/admin/simulate \
     -H "Content-Type: application/json" \
     -d '{"reset":true}'
```

### 기동 시 초기 토큰 설정

`docker-compose.yml`에서 환경변수로 설정:

```yaml
environment:
  - VALID_TOKENS=token-001,token-002,admin-token
  - RESPONSE_DELAY_MS=50    # 평균 50ms 지연 시뮬레이션
```

---

## 테스트 자동화

### 전체 WAS 자동 테스트

```bash
./scripts/run-all-tests.sh
```

**테스트 항목** (WAS별):
1. WAS 기동 확인 (TCP 포트 응답)
2. Agent 초기화 로그 감지
3. 위빙 전략 설치 로그 감지
4. 미인증 요청 차단 (401/302)
5. 유효 토큰 인증 통과 (200)
6. 무효 토큰 거부 (401/302)
7. Mock 서버 호출 통계 확인

### 결과 확인

```bash
# 테스트 결과 요약
cat test-results/summary.txt

# 특정 WAS 상세 로그
cat test-results/tomcat-9.log
```

### CI/CD 통합

```bash
# 비대화형 실행 (exit 코드 0=성공, 1=실패, 2=환경오류)
./scripts/run-all-tests.sh --only=tomcat-9 && echo "OK" || echo "FAILED"
```

---

## Agent 교체 및 반복 테스트

새 버전 Agent를 빌드 후 즉시 테스트:

```bash
# 1. onepass-agent 재빌드
cd ..
./gradlew :onepass-agent:agentJar

# 2. 자동 교체 및 컨테이너 재시작
cd onepass-agent-testbed
./scripts/replace-agent.sh              # 모든 WAS에 배포

# 특정 WAS만
./scripts/replace-agent.sh tomcat-9

# 3. 테스트 실행 (이미지 재빌드 없이)
./scripts/run-all-tests.sh --no-rebuild --keep-running
```

---

## 설정 참조

### `config/onepass-agent.properties` 주요 항목

```properties
# Mock 서버 URL (테스트베드 내부 서비스명 사용)
onepass.server.url=http://mock-onepass-server:8080

# 인증 제외 경로 (ANT 패턴)
onepass.exclude.paths=/health,/actuator/**,/public/**

# Fail-Open: 서버 연결 실패 시 요청 허용
onepass.failopen=true

# 토큰 캐시 (성능 최적화)
onepass.token.cache.enabled=true
onepass.token.cache.ttl.seconds=60
```

### 환경변수 오버라이드 (`docker-compose.yml`)

```yaml
environment:
  - ONEPASS_SERVER_URL=http://mock-onepass-server:8080
  - ONEPASS_AGENT_ENABLED=true
  - ONEPASS_LOG_LEVEL=DEBUG
```

---

## 트러블슈팅

### 컨테이너가 기동되지 않음

```bash
# 상태 확인
docker compose ps

# 특정 서비스 로그
docker compose logs --tail=50 tomcat-9

# 이미지 재빌드 (캐시 무시)
docker compose build --no-cache tomcat-9
docker compose up -d tomcat-9
```

### Agent 위빙 로그가 없음

원인 확인:

```bash
# Agent JAR 존재 여부
ls -la ./agent/

# 컨테이너 내 Agent JAR 확인
docker compose exec tomcat-9 ls -la /agent/

# JAVA_OPTS 확인
docker compose exec tomcat-9 env | grep JAVA_OPTS
```

`-javaagent` 경로가 실제 JAR 경로와 일치하는지 Dockerfile에서 확인.

### Mock 서버가 응답하지 않음

```bash
# 포트 18080 확인
curl -v http://localhost:18080/health

# 컨테이너 재시작
docker compose restart mock-onepass-server

# 로그 확인
docker compose logs mock-onepass-server
```

### jakarta.NoSuchMethodError / ClassNotFoundException

`TOMCAT_10_PLUS` 또는 `WILDFLY`에서 javax 클래스 참조 시 발생.  
→ `sample-webapp`이 jakarta namespace로 컴파일되었는지 확인.  
→ Tomcat 10+ Migration Tool 사용 필요.

### 포트 충돌 (Address already in use)

```bash
# 사용 중인 포트 확인
lsof -i :18080-18090

# docker-compose.yml에서 호스트 포트 변경
# "18084:8080" → "19084:8080" 등
```

---

## 정리

```bash
# 모든 컨테이너 종료 및 네트워크 제거
docker compose down

# 볼륨까지 제거
docker compose down -v

# 이미지까지 제거
docker compose down --rmi all
```
