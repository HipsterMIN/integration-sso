# ════════════════════════════════════════════════════════════════════════════
# idem-relay — Dockerfile (v1.0)
# PR-A1: SI 운영 배포 — Transactional Outbox 분산 릴레이 배치 컨테이너화
#
# 설계 포인트:
#   1. idem-hub/idem-registry Dockerfile과 동일한 multi-stage layered JAR 패턴
#   2. ShedLock 분산 락 기반 → 다중 Pod 배포 시 자동 페일오버
#   3. Multi-DataSource (PostgreSQL/MariaDB) 드라이버 모두 포함
#   4. 외부 기관 Webhook/Provisioning HTTP 호출용 curl + (옵션) mTLS truststore
#   5. Spring Boot Layered JAR — 변경 적은 레이어 캐시 활용
#
# 빌드:
#   DOCKER_BUILDKIT=1 docker build \
#     -t idem-relay:latest \
#     -f idem-relay/Dockerfile .
#
#   DOCKER_BUILDKIT=1 docker build \
#     --build-arg APP_VERSION=1.0.0 \
#     --build-arg GIT_SHA=$(git rev-parse --short HEAD) \
#     --build-arg BUILD_DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ) \
#     -t idem-relay:1.0.0 \
#     -f idem-relay/Dockerfile .
# ════════════════════════════════════════════════════════════════════════════

# ── 빌드 인수 ────────────────────────────────────────────────────────────────
ARG JAVA_VERSION=21
ARG APP_VERSION=latest
ARG GIT_SHA=unknown
ARG BUILD_DATE=unknown

# ── Stage 1: Dependency Layer ────────────────────────────────────────────────
# Gradle 의존성만 다운로드 → 소스 변경 시 재다운로드 방지
FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS deps
WORKDIR /workspace

# BuildKit 캐시 마운트를 위한 환경 변수
ENV GRADLE_USER_HOME=/root/.gradle
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.parallel=true"

# Gradle Wrapper + 빌드 스크립트만 먼저 복사 (소스 변경과 분리)
COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle.kts .
COPY build.gradle.kts .
# settings.gradle.kts include() 한 모든 모듈의 빌드 스크립트 — Gradle 설정 평가에 필요.
# (목표 모듈 외의 src 는 Stage 2 에서 복사하지 않으므로 컨텍스트 크기는 최소화됨.)
COPY idem-common/build.gradle.kts idem-common/
COPY idem-gate/build.gradle.kts          idem-gate/
COPY idem-registry/build.gradle.kts            idem-registry/
COPY idem-hub/build.gradle.kts             idem-hub/
COPY idem-authz/build.gradle.kts          idem-authz/
COPY idem-console/build.gradle.kts      idem-console/
COPY idem-tenant-sample/build.gradle.kts     idem-tenant-sample/
COPY idem-sdk-java/build.gradle.kts idem-sdk-java/
COPY idem-relay/build.gradle.kts idem-relay/
COPY idem-agent/build.gradle.kts   idem-agent/
COPY plugins/idem-plugin-mock-auth/build.gradle.kts plugins/idem-plugin-mock-auth/
COPY plugins/idem-plugin-nice-oacx/build.gradle.kts plugins/idem-plugin-nice-oacx/

# Gradle Wrapper 실행 권한 + 의존성 사전 다운로드
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && \
    ./gradlew :idem-relay:dependencies --no-daemon --quiet || true

# ── Stage 2: Build ────────────────────────────────────────────────────────────
FROM deps AS builder

# 소스 복사 (의존성 레이어와 분리 — 소스 변경 시 이 레이어부터 재빌드)
COPY idem-common/src/ idem-common/src/
COPY idem-relay/src/ idem-relay/src/

# Layered JAR 빌드 (Spring Boot 2.3+ 기본 지원)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :idem-relay:bootJar \
        -x test \
        --no-daemon \
        --parallel \
        --build-cache && \
    # JAR 레이어 추출 (레이어별 Docker COPY 최적화)
    mkdir -p /workspace/layers && \
    java -Djarmode=layertools \
         -jar /workspace/idem-relay/build/libs/idem-relay-*.jar \
         extract --destination /workspace/layers

# ── Stage 3: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime

# OCI 이미지 레이블 (표준 메타데이터)
ARG APP_VERSION
ARG GIT_SHA
ARG BUILD_DATE
LABEL org.opencontainers.image.title="Idem Platform — Outbox Relay Batch" \
      org.opencontainers.image.description="Transactional Outbox 분산 릴레이 배치 — ShedLock 기반 단일 인스턴스 보장, idem-hub/idem-registry/idem-gate 멀티 DataSource 릴레이" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/HipsterMIN/integration-sso" \
      org.opencontainers.image.vendor="Idem" \
      org.opencontainers.image.licenses="Proprietary"

WORKDIR /app

# 보안: 전용 non-root 사용자 생성
# 추가 패키지:
#   - curl: HEALTHCHECK용
#   - ca-certificates: 외부 기관 Webhook HTTPS 검증 (provisioning, webhook 릴레이)
#   - tzdata: Asia/Seoul 타임존 설정 (Outbox claimed_at 시각 정합성)
RUN addgroup -S batch && \
    adduser -S batch -G batch -h /app -s /sbin/nologin && \
    apk add --no-cache curl ca-certificates tzdata && \
    cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone

# Spring Boot Layered JAR 레이어 순서대로 복사
# (변경 빈도 낮은 레이어 → 높은 레이어 순서 — 캐시 효율 극대화)
COPY --from=builder --chown=batch:batch /workspace/layers/dependencies/          ./
COPY --from=builder --chown=batch:batch /workspace/layers/spring-boot-loader/    ./
COPY --from=builder --chown=batch:batch /workspace/layers/snapshot-dependencies/ ./
COPY --from=builder --chown=batch:batch /workspace/layers/application/           ./

USER batch

# Actuator 포트 (application.yml: server.port=8090)
EXPOSE 8090

# JVM 최적화 옵션 (idem-hub와 동일 패턴)
# - G1GC: 낮은 GC 중단 시간 (배치 폴링 500ms 주기 → 짧은 STW 필수)
# - MaxRAMPercentage: 컨테이너 메모리 한도 자동 인식 (K8s 친화적)
# - 배치는 idem-hub 대비 메모리 사용량 낮음 → InitialRAMPercentage=40
ENV JAVA_OPTS="\
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=40.0 \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=Asia/Seoul \
    -Dspring.backgroundpreinitializer.ignore=true"

# Spring Boot Layered JAR 진입점
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

# 헬스체크: ShedLock Redis/JDBC 연결 + DataSource 3개 모두 검사
# - start-period 60s: 배치 부팅 시 멀티 DataSource 초기화 + Flyway V19 마이그레이션 대기
# - interval 15s: idem-hub(10s)보다 길게 — 배치는 즉시성 요구도 낮음
HEALTHCHECK --interval=15s \
            --timeout=5s \
            --start-period=60s \
            --retries=3 \
    CMD curl -sf http://localhost:8090/actuator/health/liveness | \
        grep -q '"status":"UP"' || exit 1
