# ============================================================
# Agency-Stub — Dockerfile
# 유관기관 OIDC 클라이언트 시뮬레이터
# 멀티스테이지 빌드: Gradle 빌드 → JRE 런타임 이미지
#
# 빌드 명령 (프로젝트 루트에서):
#   ./gradlew :agency-stub:bootJar -x test
#   docker build -f agency-stub/Dockerfile -t onepass-agency-stub:latest .
# ============================================================

# ── Stage 1: Build ───────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Gradle Wrapper + 빌드 스크립트 먼저 복사 (레이어 캐시 최적화)
COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle.kts .
COPY build.gradle.kts .

# settings.gradle.kts include() 한 모든 모듈의 빌드 스크립트 — Gradle 설정 평가에 필요.
COPY platform-common/build.gradle.kts platform-common/
COPY q-sign/build.gradle.kts          q-sign/
COPY q-im/build.gradle.kts            q-im/
COPY ido/build.gradle.kts             ido/
COPY onepass-fe/build.gradle.kts      onepass-fe/
COPY agency-stub/build.gradle.kts     agency-stub/
COPY onepass-agency-sdk/build.gradle.kts onepass-agency-sdk/
COPY outbox-relay-batch/build.gradle.kts outbox-relay-batch/
COPY onepass-agent/build.gradle.kts   onepass-agent/

# 소스 복사 (목표 모듈 + 의존 platform-common 만)
COPY platform-common/src/ platform-common/src/
COPY agency-stub/src/ agency-stub/src/

RUN chmod +x gradlew && \
    ./gradlew :agency-stub:bootJar -x test --no-daemon --parallel

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# 보안: non-root 사용자
RUN addgroup -S agency && adduser -S agency -G agency
USER agency

# 빌드 산출물 복사
COPY --from=builder /workspace/agency-stub/build/libs/agency-stub-*.jar app.jar

# Actuator health probe 포트
EXPOSE 8084

# JVM 최적화 옵션
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
               -Djava.security.egd=file:/dev/./urandom \
               -Dfile.encoding=UTF-8 -Duser.timezone=UTC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
