# ════════════════════════════════════════════════════════════════════════════
# IdO — Dockerfile (최적화 v2.0)
# S8-T6: Docker 멀티스테이지 빌드 최적화
#
# 주요 개선사항:
#   1. Gradle 빌드 캐시 마운트 (BuildKit --mount=type=cache)
#   2. Spring Boot Layered JAR → 레이어별 COPY (변경 적은 레이어 캐시 활용)
#   3. OCI 이미지 레이블 표준 (org.opencontainers.image.*)
#   4. HEALTHCHECK 내장
#   5. 빌드 ARG (APP_VERSION, GIT_SHA) 주입 지원
#
# 빌드:
#   DOCKER_BUILDKIT=1 docker build -t ido:latest -f ido/Dockerfile .
#   DOCKER_BUILDKIT=1 docker build \
#     --build-arg APP_VERSION=1.0.0 \
#     --build-arg GIT_SHA=$(git rev-parse --short HEAD) \
#     -t ido:1.0.0 -f ido/Dockerfile .
# ════════════════════════════════════════════════════════════════════════════

# ── 빌드 인수 ────────────────────────────────────────────────────────────────
ARG JAVA_VERSION=21
ARG APP_VERSION=latest
ARG GIT_SHA=unknown
ARG BUILD_DATE=unknown
ARG GRADLE_VERSION=9.5

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
COPY platform-common/build.gradle.kts platform-common/
COPY q-sign/build.gradle.kts          q-sign/
COPY q-im/build.gradle.kts            q-im/
COPY ido/build.gradle.kts             ido/
COPY onepass-support/build.gradle.kts onepass-support/
COPY onepass-fe/build.gradle.kts      onepass-fe/
COPY agency-stub/build.gradle.kts     agency-stub/
COPY onepass-agency-sdk/build.gradle.kts onepass-agency-sdk/
COPY outbox-relay-batch/build.gradle.kts outbox-relay-batch/
COPY onepass-agent/build.gradle.kts   onepass-agent/
# ido 로컬 libs 디렉토리 (OACX SDK 등)
COPY ido/libs/ ido/libs/

# Gradle Wrapper 실행 권한 + 의존성 사전 다운로드
# BuildKit 캐시 마운트: ~/.gradle 재사용으로 빌드 시간 단축
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && \
    ./gradlew :ido:dependencies --no-daemon --quiet || true

# ── Stage 2: Build ────────────────────────────────────────────────────────────
FROM deps AS builder

# 소스 복사 (의존성 레이어와 분리 — 소스 변경 시 이 레이어부터 재빌드)
COPY platform-common/src/ platform-common/src/
COPY ido/src/ ido/src/

# Layered JAR 빌드 (Spring Boot 2.3+ 기본 지원)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :ido:bootJar \
        -x test \
        --no-daemon \
        --parallel \
        --build-cache && \
    # JAR 레이어 추출 (레이어별 Docker COPY 최적화)
    mkdir -p /workspace/layers && \
    java -Djarmode=layertools \
         -jar /workspace/ido/build/libs/ido-*.jar \
         extract --destination /workspace/layers

# ── Stage 3: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime

# OCI 이미지 레이블 (표준 메타데이터)
ARG APP_VERSION
ARG GIT_SHA
ARG BUILD_DATE
LABEL org.opencontainers.image.title="OnePass Platform — IdO Service" \
      org.opencontainers.image.description="IdO (Identity Orchestrator) — Handoff·Auth·Rate Limiter 서비스" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/HipsterMIN/integration-sso" \
      org.opencontainers.image.vendor="SMES" \
      org.opencontainers.image.licenses="Proprietary"

WORKDIR /app

# 보안: 전용 non-root 사용자 생성
RUN addgroup -S ido && \
    adduser -S ido -G ido -h /app -s /sbin/nologin && \
    # 헬스체크용 curl 설치
    apk add --no-cache curl

# Spring Boot Layered JAR 레이어 순서대로 복사
# (변경 빈도 낮은 레이어 → 높은 레이어 순서 — 캐시 효율 극대화)
COPY --from=builder --chown=ido:ido /workspace/layers/dependencies/          ./
COPY --from=builder --chown=ido:ido /workspace/layers/spring-boot-loader/    ./
COPY --from=builder --chown=ido:ido /workspace/layers/snapshot-dependencies/ ./
COPY --from=builder --chown=ido:ido /workspace/layers/application/           ./

USER ido

# 서비스 포트 (Actuator 포함)
EXPOSE 8083

# JVM 최적화 옵션
# - G1GC: 낮은 GC 중단 시간 보장 (Handoff SLO p95 < 2000ms)
# - MaxRAMPercentage: 컨테이너 메모리 한도 자동 인식 (K8s 친화적)
# - NativeMemoryTracking: 메모리 진단 지원
ENV JAVA_OPTS="\
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=Asia/Seoul \
    -Dspring.backgroundpreinitializer.ignore=true"

# Spring Boot Layered JAR 진입점 (org.springframework.boot.loader.launch.JarLauncher)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

# 헬스체크: 30초 후 시작, 10초 간격, 3회 실패 시 unhealthy
HEALTHCHECK --interval=10s \
            --timeout=5s \
            --start-period=30s \
            --retries=3 \
    CMD curl -sf http://localhost:8083/actuator/health | \
        grep -q '"status":"UP"' || exit 1
