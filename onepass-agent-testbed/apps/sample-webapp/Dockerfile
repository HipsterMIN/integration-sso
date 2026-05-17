# ─────────────────────────────────────────────────────────────────────────────
# sample-webapp Dockerfile
#
# Tomcat 9 기반으로 빌드 → WAR 생성 후 각 WAS Dockerfile에서 COPY --from=webapp
# 또는 독립 실행: docker build -t sample-webapp . && docker run ...
# ─────────────────────────────────────────────────────────────────────────────
FROM tomcat:9.0.85-jdk11-temurin-jammy AS build

# Ant 설치 (WAR 빌드용)
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends ant && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY . /build/

# WAR 빌드 (Tomcat의 servlet-api.jar 사용)
RUN ant -Dservlet.jar=/usr/local/tomcat/lib/servlet-api.jar war

# ─────────────────────────────────────────────────────────────────────────────
# 실행 이미지는 docker-compose에서 각 WAS Dockerfile이 담당하므로
# 이 Dockerfile은 WAR 아티팩트 생성 전용
# docker-compose.yml에서: COPY --from=sample-webapp /build/build/sample-webapp.war ...
# ─────────────────────────────────────────────────────────────────────────────
FROM scratch AS artifact
COPY --from=build /build/build/sample-webapp.war /sample-webapp.war
