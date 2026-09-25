# OnePass 통합인증 플랫폼 — 로컬 개발 환경 구동 가이드

> **문서 분류**: 개발자 운영 가이드  
> **버전**: v1.3.0  
> **최종 수정**: 2026-05-09  
> **대상 독자**: 백엔드 개발자, 프론트엔드 개발자, DevOps  
> **관련 모듈**: `q-sign`, `q-im`, `ido`, `idem-console`, `agency-stub`, `platform-common`

> **v1.3.0 변경 내역** (2026-05-09)
> - §8.4 v1.9.x 신규 기능 동작 확인 절차 추가 (기관 이벤트 폴링 API, Provider 라우팅, Broker Audit Log)
> - §9 서비스 포트 표 — `docs/spec/` 신규 문서 디렉토리 링크 추가
> - §10 DB 스키마 구조 — IdO V10 신규 테이블 반영 (`provider_circuit_config`, auth_result 확장 4컬럼)
> - §12 Redis 키 패턴 표 — `idem:provider-config:*` 캐시 키 추가
> - 부록 A 체크리스트 — v1.9.3 기준 항목 추가 (기관 이벤트 폴링, Provider CB)
> - 부록 B 환경변수 표 — `IDEM_HUB_AGENCY_SUBJECT_SECRET` 행 추가
>
> **v1.2.0 변경 내역** (2026-05-08)
> - §8.2 PoC 흐름 테스트에 Admin API / PKCE / Rate Limiter / MemberLookup curl 예제 추가
> - §9 DB 스키마 구조에 IdO V9 신규 테이블 4개 추가 (`crypto_key_registry`, `agency_rate_limit_config`, `agency_meta_history`, `member_lookup_log`)
> - §11.13 Rate Limiter / PKCE / Admin API 오류 트러블슈팅 섹션 실제 내용 추가
> - §12.4 Redis 키 패턴 표 추가 (Rate Limiter, PKCE, AES 키 로테이션, Idempotency)
> - §12.3 MariaDB 유용한 명령어에 `user_status_history`, `crypto_key_version` 조회 예제 추가
> - 부록 A 체크리스트에 Monitoring 스택 / Admin API 확인 항목 추가
> - 부록 B 환경변수 표에 Q-IM 암호화 키 (`IDEM_REGISTRY_CI_*`, `IDEM_REGISTRY_DI_SECRET`) 행 추가
>
> **v1.1.0 변경 내역** (2026-05-08)
> - Monitoring 스택 추가 (Prometheus / Grafana / Loki / Promtail — profile: monitoring)
> - 기관 Admin API 엔드포인트 동작 확인 절차 추가
> - Redis Rate Limiter / PKCE / AES 키 로테이션 키 패턴 추가
> - IdO DB 마이그레이션 V9 / Q-IM V3 테이블 목록 업데이트
> - 디렉토리 구조에 `infra/monitoring/` 추가
> - 서비스 포트 표 업데이트 (Prometheus :9090, Grafana :3002, Loki :3100)
> - 트러블슈팅 §11.13 Rate Limiter·PKCE·Admin API 오류 추가

---

## 목차

1. [사전 요구사항](#1-사전-요구사항)
   - 1.1 [Windows 설정](#11-windows-설정)
   - 1.2 [macOS 설정](#12-macos-설정)
   - 1.3 [Linux 설정](#13-linux-설정)
2. [프로젝트 클론 및 디렉토리 구조](#2-프로젝트-클론-및-디렉토리-구조)
3. [Step 1 — 인프라 기동 (Docker Compose)](#3-step-1--인프라-기동-docker-compose)
4. [Step 2 — 인프라 기동 확인](#4-step-2--인프라-기동-확인)
5. [Step 3 — Java 백엔드 빌드](#5-step-3--java-백엔드-빌드)
6. [Step 4 — 백엔드 서비스 기동](#6-step-4--백엔드-서비스-기동)
7. [Step 5 — 프론트엔드 기동](#7-step-5--프론트엔드-기동)
8. [Step 6 — 전체 동작 확인](#8-step-6--전체-동작-확인)
   - 8.2 [PoC 흐름 기본 테스트](#82-poc-흐름-기본-테스트)
   - 8.3 [v1.8.0 신규 기능 동작 확인](#83-v180-신규-기능-동작-확인)
   - 8.4 [v1.9.x 신규 기능 동작 확인](#84-v19x-신규-기능-동작-확인-★-v190v193)
9. [서비스 포트 및 접속 URL 정리](#9-서비스-포트-및-접속-url-정리)
10. [IDE 설정 가이드](#10-ide-설정-가이드)
11. [자주 발생하는 오류 및 해결 방법](#11-자주-발생하는-오류-및-해결-방법)
    - 11.1 [Docker 관련 오류](#111-docker-관련-오류)
    - 11.2 [MariaDB 관련 오류 (Q-IM 전용)](#112-mariadb-관련-오류-q-im-전용)
    - 11.3 [PostgreSQL 관련 오류](#113-postgresql-관련-오류)
    - 11.4 [Kafka / Zookeeper 관련 오류](#114-kafka--zookeeper-관련-오류)
    - 11.5 [Redis 관련 오류](#115-redis-관련-오류)
    - 11.6 [Gradle / Java 빌드 오류](#116-gradle--java-빌드-오류)
    - 11.7 [Spring Boot 기동 오류](#117-spring-boot-기동-오류)
    - 11.8 [Flyway 마이그레이션 오류](#118-flyway-마이그레이션-오류)
    - 11.9 [프론트엔드 (Node.js / Yarn) 오류](#119-프론트엔드-nodejs--yarn-오류)
    - 11.10 [Keycloak 관련 오류](#1110-keycloak-관련-오류)
    - 11.11 [Windows 전용 오류](#1111-windows-전용-오류)
    - 11.12 [macOS 전용 오류](#1112-macos-전용-오류)
    - 11.13 [Rate Limiter / PKCE / Admin API / AES 키 로테이션 오류](#1113-rate-limiter--pkce--admin-api-오류)
12. [개발 Tips 및 유용한 명령어](#12-개발-tips-및-유용한-명령어)
    - 12.1 [Gradle 빠른 명령어](#121-gradle-빠른-명령어-모음)
    - 12.2 [Docker 명령어](#122-docker-유용한-명령어)
    - 12.3 [MariaDB 명령어 (Q-IM)](#123-mariadb-유용한-명령어-q-im-전용)
    - 12.4 [PostgreSQL 명령어](#124-postgresql-유용한-명령어)
    - 12.5 [Redis 명령어](#125-redis-유용한-명령어)
    - 12.6 [Redis 키 패턴 전체 목록](#126-redis-키-패턴-전체-목록-v180-기준)
    - 12.7 [Kafka 명령어](#127-kafka-유용한-명령어)
    - 12.8 [Spring Boot Actuator](#128-spring-boot-actuator-엔드포인트)
13. [서비스 종료 방법](#13-서비스-종료-방법)

---

## 1. 사전 요구사항

### 전체 공통 필수 소프트웨어

| 소프트웨어 | 최소 버전 | 권장 버전 | 용도 |
|-----------|---------|---------|------|
| **JDK** | 21 | Eclipse Temurin 21 LTS | Spring Boot 백엔드 |
| **Docker** | 24.0 | 최신 안정 버전 | 인프라 컨테이너 |
| **Docker Compose** | v2.0 | v2.24 이상 | 인프라 오케스트레이션 |
| **Git** | 2.30 | 최신 안정 버전 | 소스 코드 관리 |
| **Node.js** | 20.0 LTS | 20.14.0 LTS | React 프론트엔드 빌드 |
| **Yarn** | 1.22 | 1.22.22 | 패키지 매니저 |

> ⚠️ **중요**: JDK는 반드시 **21** 버전이어야 합니다. 17이나 11은 동작하지 않습니다.  
> ⚠️ **중요**: Gradle Wrapper가 포함되어 있으므로 **Gradle을 별도 설치할 필요가 없습니다.**  
> ⚠️ **중요**: Docker Compose는 **v2** 이상 (명령어: `docker compose`, 하이픈 없음)을 사용합니다.

---

### 1.1 Windows 설정

#### A. JDK 21 설치 (Eclipse Temurin 권장)

1. **공식 다운로드**: https://adoptium.net/temurin/releases/?version=21
2. **Windows x64 Installer (.msi)** 다운로드 후 실행
3. 설치 중 **"Add to PATH"**, **"Set JAVA_HOME"** 옵션 반드시 체크
4. 설치 완료 후 **PowerShell** (관리자 권한) 에서 확인:

```powershell
java -version
# 출력 예시:
# openjdk version "21.0.7" 2025-04-15 LTS
# OpenJDK Runtime Environment Temurin-21.0.7+6 (build 21.0.7+6-LTS)

echo $env:JAVA_HOME
# 출력 예시: C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot
```

5. JAVA_HOME이 비어 있으면 수동 설정:

```powershell
# 시스템 환경변수 등록 (관리자 PowerShell)
[System.Environment]::SetEnvironmentVariable(
  "JAVA_HOME",
  "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot",
  "Machine"
)
# PATH에 추가
$path = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
[System.Environment]::SetEnvironmentVariable(
  "Path",
  "$path;$env:JAVA_HOME\bin",
  "Machine"
)
```

6. PowerShell을 **새로 열고** 재확인:

```powershell
java -version
javac -version
```

#### B. Docker Desktop 설치

1. **공식 다운로드**: https://www.docker.com/products/docker-desktop/
2. Windows 요구사항 확인:
   - **WSL 2 방식 (권장)**: Windows 10 21H2 이상, Windows 11
   - **Hyper-V 방식**: Windows 10 Pro/Enterprise
3. Docker Desktop 설치 후 재부팅
4. **WSL 2 활성화** (아직 안 된 경우):

```powershell
# 관리자 PowerShell에서 실행
wsl --install
wsl --set-default-version 2
```

5. Docker Desktop 실행 후 시스템 트레이에서 **초록색 고래 아이콘** 확인
6. PowerShell에서 확인:

```powershell
docker --version
# Docker version 26.x.x

docker compose version
# Docker Compose version v2.x.x
```

#### C. Node.js 20 LTS 설치

1. **공식 다운로드**: https://nodejs.org/en/download (20.x LTS 선택)
2. Windows Installer (.msi) 실행
3. 설치 옵션 중 **"Add to PATH"** 체크
4. **PowerShell 재시작** 후 확인:

```powershell
node --version
# v20.x.x

npm --version
# 10.x.x
```

5. Yarn 설치:

```powershell
npm install -g yarn
yarn --version
# 1.22.x
```

#### D. Git 설치

1. **공식 다운로드**: https://git-scm.com/download/win
2. 설치 시 주요 옵션:
   - **Git Bash** 포함 선택 (권장 — Unix 명령어 사용 가능)
   - **"Use Git from the Windows Command Prompt"** 선택
   - **Line endings**: "Checkout Windows-style, commit Unix-style" 선택
3. 확인:

```powershell
git --version
# git version 2.x.x
```

#### E. Windows Terminal 설치 (권장)

Microsoft Store에서 **Windows Terminal** 설치 → PowerShell, Git Bash, WSL을 탭으로 관리 가능.

> 💡 **Windows 개발 팁**: 이후 모든 명령어는 **Git Bash** 또는 **WSL 터미널**에서 실행하는 것을 권장합니다. PowerShell도 가능하나 일부 Gradle 명령어는 `./gradlew` 대신 `.\gradlew.bat`으로 실행해야 합니다.

---

### 1.2 macOS 설정

#### A. Homebrew 설치 (패키지 매니저)

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

설치 후 PATH 등록 (Apple Silicon M1/M2/M3의 경우):

```bash
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zshrc
source ~/.zshrc
```

Intel Mac의 경우 `/usr/local/bin/brew`가 기본 PATH에 포함됩니다.

#### B. JDK 21 설치

```bash
# SDKMAN 방식 (권장 — 여러 JDK 버전 관리 가능)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

sdk install java 21.0.7-tem
sdk use java 21.0.7-tem
sdk default java 21.0.7-tem

# 확인
java -version
# openjdk version "21.0.7" 2025-04-15 LTS
```

또는 Homebrew 방식:

```bash
brew install --cask temurin@21
# 확인
java -version
```

JAVA_HOME 설정 (~/.zshrc 또는 ~/.bash_profile):

```bash
# Apple Silicon
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH=$JAVA_HOME/bin:$PATH
```

```bash
source ~/.zshrc   # 또는 source ~/.bash_profile
java -version
echo $JAVA_HOME
```

#### C. Docker Desktop 설치

1. **공식 다운로드**: https://www.docker.com/products/docker-desktop/
2. **Apple Silicon (M1/M2/M3)**: "Apple Silicon" 버전 다운로드
3. **Intel Mac**: "Intel Chip" 버전 다운로드
4. .dmg 파일 실행 → Applications 폴더로 드래그
5. Docker Desktop 실행 후 메뉴바 고래 아이콘 확인
6. 확인:

```bash
docker --version
docker compose version
```

> 💡 **macOS Docker 리소스 설정**: Docker Desktop → Settings → Resources  
> - Memory: 최소 **6GB** (권장 8GB 이상)  
> - CPUs: 최소 **4**  
> - Disk image size: 64GB 이상

#### D. Node.js 20 LTS 설치

```bash
# nvm 방식 (권장 — 버전 전환 가능)
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
source ~/.zshrc   # 또는 ~/.bash_profile

nvm install 20
nvm use 20
nvm alias default 20

# 확인
node --version
# v20.x.x

# Yarn 설치
npm install -g yarn
yarn --version
# 1.22.x
```

또는 Homebrew 방식:

```bash
brew install node@20
echo 'export PATH="/opt/homebrew/opt/node@20/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

#### E. Git 설치

macOS는 기본 Git이 설치되어 있으나 최신 버전 권장:

```bash
brew install git
git --version
```

---

### 1.3 Linux 설정

#### Ubuntu / Debian 계열

```bash
# 패키지 목록 업데이트
sudo apt-get update && sudo apt-get upgrade -y

# 기본 도구
sudo apt-get install -y curl wget git unzip
```

#### A. JDK 21 설치 (Eclipse Temurin)

```bash
# Adoptium GPG 키 및 저장소 등록
wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo apt-key add -
echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -sc) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list

sudo apt-get update
sudo apt-get install -y temurin-21-jdk

# 확인
java -version
javac -version

# JAVA_HOME 설정
echo 'export JAVA_HOME=/usr/lib/jvm/temurin-21-amd64' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

CentOS / RHEL / Fedora 계열:

```bash
# RPM 패키지 설치 (Fedora 예시)
sudo dnf install -y java-21-openjdk-devel
# 또는 직접 다운로드
# https://adoptium.net/temurin/releases/?version=21 에서 RPM 다운로드

java -version
```

#### B. Docker 설치

```bash
# Docker 공식 설치 스크립트 (Ubuntu/Debian)
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# 현재 사용자를 docker 그룹에 추가 (sudo 없이 docker 사용)
sudo usermod -aG docker $USER
newgrp docker

# Docker 서비스 시작 및 자동 시작 등록
sudo systemctl enable docker
sudo systemctl start docker

# 확인
docker --version
docker compose version
```

> ⚠️ `newgrp docker` 또는 **로그아웃 후 재로그인** 해야 그룹 권한이 적용됩니다.

#### C. Node.js 20 LTS 설치

```bash
# nvm 방식 (권장)
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
source ~/.bashrc

nvm install 20
nvm use 20
nvm alias default 20

node --version
npm install -g yarn
yarn --version
```

또는 NodeSource 공식 저장소:

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs
npm install -g yarn
```

---

## 2. 프로젝트 클론 및 디렉토리 구조

### 2.1 소스 코드 클론

```bash
git clone https://github.com/HipsterMIN/integration-sso.git
cd integration-sso
```

> ⚠️ **Windows Git Bash / WSL 사용자**: 클론 경로에 **한글, 공백, 특수문자**가 없는 경로를 권장합니다.  
> 예: `C:\dev\integration-sso` (권장) / `C:\Users\홍길동\문서\project` (비권장)

### 2.2 브랜치 확인

```bash
git branch -a
git checkout genspark_ai_developer   # 개발 브랜치
# 또는
git checkout main                    # 안정 브랜치
```

### 2.3 디렉토리 구조 확인

```
integration-sso/                      ← 프로젝트 루트
├── gradlew                           ← Linux/macOS Gradle Wrapper 실행파일
├── gradlew.bat                       ← Windows Gradle Wrapper 실행파일
├── build.gradle.kts                  ← 루트 Gradle 빌드 설정
├── settings.gradle.kts               ← 멀티프로젝트 모듈 등록
│
├── idem-common/                  ← 공통 도메인·이벤트·에러코드 (JAR)
├── idem-gate/                           ← 인증 SoR (port 8081)
├── idem-registry/                             ← 식별 SoR (port 8082)
├── idem-hub/                              ← 정책 오케스트레이터 + FE BFF (port 8083)
├── idem-tenant-sample/                      ← 기관 로컬 세션 시뮬레이터 (port 8084)
│
├── idem-console/                       ← 순수 React SPA (Node.js 모듈)
│   ├── build.gradle.kts
│   └── frontend/
│       ├── package.json
│       ├── webpack.config.js
│       └── src/
│
├── infra/
│   ├── docker/
│   │   ├── docker-compose.yml        ← ★ 인프라 기동 파일
│   │   ├── init-db.sql               ← PostgreSQL 초기 스키마 (Q-IM 제외)
│   │   ├── kafka/
│   │   │   └── create-topics.sh      ← Kafka 토픽 초기화
│   │   ├── mariadb/
│   │   │   └── mariadb.cnf           ← MariaDB 튜닝 설정 (Q-IM 전용)
│   │   ├── nginx/
│   │   │   └── nginx.conf
│   │   ├── postgres/
│   │   │   ├── postgresql.conf
│   │   │   └── pg_hba.conf
│   │   └── redis/
│   │       └── redis.conf
│   └── monitoring/                   ← ★ v1.1.0 신규 — Monitoring 설정
│       ├── prometheus/
│       │   ├── prometheus.yml        ← scrape 설정 (idem-hub/qim/qsign/agency-stub)
│       │   └── alert_rules.yml       ← 알림 규칙 (오류율/응답시간/Rate Limit)
│       ├── grafana/
│       │   └── provisioning/
│       │       ├── datasources/      ← Prometheus + Loki 데이터소스 자동 등록
│       │       └── dashboards/       ← 대시보드 프로비저닝
│       ├── loki/
│       │   └── loki-config.yml       ← 로그 수집 설정
│       └── promtail/
│           └── promtail-config.yml   ← Docker 컨테이너 로그 수집
│
└── docs/                             ← 설계 문서
```

### 2.4 Gradle Wrapper 실행 권한 설정 (Linux/macOS)

```bash
chmod +x gradlew
```

> ⚠️ **Windows**: `gradlew.bat`을 사용하거나, Git Bash에서 `./gradlew`로 실행합니다.  
> `gradlew.bat`은 CMD/PowerShell에서, `./gradlew`는 Git Bash/WSL에서 사용합니다.

---

## 3. Step 1 — 인프라 기동 (Docker Compose)

> 📋 **기동 순서**: 인프라(MariaDB + PostgreSQL → Redis → Zookeeper → Kafka) → 백엔드 → 프론트엔드  
> 인프라가 완전히 준비되지 않은 상태에서 Spring Boot를 기동하면 연결 오류가 발생합니다.
>
> ⚠️ **DB 분리**: Q-IM은 **MariaDB 11** (port 3306, 별도 컨테이너)을 사용합니다.  
> Q-Sign / IdO / agency-stub / Keycloak은 **PostgreSQL 16** (port 5432)을 공유합니다.

### 3.1 docker-compose.yml 위치

모든 Docker Compose 명령은 **프로젝트 루트**에서 아래 경로를 지정하여 실행합니다.

```
infra/docker/docker-compose.yml
```

### 3.2 기본 인프라 기동 (필수)

```bash
# 프로젝트 루트에서 실행
docker compose -f infra/docker/docker-compose.yml up -d
```

이 명령으로 기동되는 서비스:
- `idem-mariadb` — **MariaDB 11.4 (port 3306) — Q-IM 전용**
- `idem-postgres` — PostgreSQL 16 (port 5432) — Q-Sign / IdO / agency-stub / Keycloak 공용
- `idem-redis` — Redis 7.2 (port 6379)
- `idem-zookeeper` — Zookeeper (port 2181)
- `idem-kafka` — Kafka Broker (port 9092)
- `idem-kafka-init` — Kafka 토픽 초기화 (one-shot, 완료 후 종료)
- `idem-kafka-ui` — Kafka UI 모니터링 (port 8090)
- `idem-redis-insight` — Redis 데이터 뷰어 (port 5540)

#### Windows (PowerShell / CMD)

```powershell
# PowerShell 또는 CMD
docker compose -f infra/docker/docker-compose.yml up -d
```

#### Windows (Git Bash)

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

#### macOS / Linux

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

### 3.3 선택적 프로파일 기동

```bash
# pgAdmin 4 + Adminer 포함 기동 (DB GUI 관리 도구)
# pgAdmin 4 (port 5050): PostgreSQL 관리
# Adminer   (port 8091): MariaDB(Q-IM) 관리 — 서버: mariadb / 사용자: qim / 암호: qim / DB: qim
docker compose -f infra/docker/docker-compose.yml --profile tools up -d

# Keycloak 포함 기동 (OIDC 브로커 모드 사용 시, port 8088)
docker compose -f infra/docker/docker-compose.yml --profile keycloak up -d

# Schema Registry 포함 기동 (Avro 스키마 관리, port 8085)
docker compose -f infra/docker/docker-compose.yml --profile schema up -d

# ★ v1.1.0 신규: Monitoring 스택 기동 (Prometheus + Grafana + Loki + Promtail)
# - Prometheus (port 9090): 메트릭 수집 / Grafana (port 3002): 대시보드
# - Loki (port 3100): 로그 수집 / Promtail: Docker 로그 수집
docker compose -f infra/docker/docker-compose.yml --profile monitoring up -d

# 전체 동시 기동 (인프라 + 도구 + Keycloak + Monitoring)
docker compose -f infra/docker/docker-compose.yml \
  --profile tools \
  --profile keycloak \
  --profile monitoring \
  up -d
```

> 💡 **프로파일 정리표**
>
> | 프로파일 | 포함 서비스 | 용도 |
> |---------|-----------|------|
> | *(없음)* | MariaDB, PostgreSQL, Redis, Zookeeper, Kafka, Kafka UI, Redis Insight | **필수 기반 인프라** |
> | `tools` | pgAdmin 4, Adminer | DB GUI 관리 |
> | `schema` | Schema Registry | Avro 스키마 |
> | `keycloak` | Keycloak | OIDC 브로커 |
> | `monitoring` | Prometheus, Grafana, Loki, Promtail | **★ 신규** 모니터링 |
> | `app` | idem-gate, idem-registry, idem-hub, idem-tenant-sample | Docker 이미지 실행 |
> | `optionB` | idem-console (Nginx) | 프로덕션 FE |

### 3.4 기동 로그 실시간 확인

```bash
# 전체 컨테이너 로그 실시간 출력
docker compose -f infra/docker/docker-compose.yml logs -f

# 특정 서비스만 확인
docker compose -f infra/docker/docker-compose.yml logs -f postgres
docker compose -f infra/docker/docker-compose.yml logs -f kafka
docker compose -f infra/docker/docker-compose.yml logs -f kafka-init
```

---

## 4. Step 2 — 인프라 기동 확인

> ⚠️ **반드시** 모든 인프라가 `healthy` 상태가 된 후 백엔드를 기동하세요.  
> Kafka는 기동에 **30~60초** 가 소요될 수 있습니다.

### 4.1 컨테이너 상태 확인

```bash
docker compose -f infra/docker/docker-compose.yml ps
```

**정상 상태 예시:**

```
NAME                    STATUS              PORTS
idem-mariadb         Up (healthy)        0.0.0.0:3306->3306/tcp   ← Q-IM 전용 MariaDB
idem-postgres        Up (healthy)        0.0.0.0:5432->5432/tcp
idem-redis           Up (healthy)        0.0.0.0:6379->6379/tcp
idem-zookeeper       Up (healthy)        0.0.0.0:2181->2181/tcp
idem-kafka           Up (healthy)        0.0.0.0:9092->9092/tcp
idem-kafka-init      Exited (0)                                     ← 0으로 종료 = 정상
idem-kafka-ui        Up (healthy)        0.0.0.0:8090->8080/tcp
idem-redis-insight   Up                  0.0.0.0:5540->5540/tcp
```

> ⚠️ `kafka-init`의 exit code가 **0** 이어야 정상입니다. **1** 이면 토픽 생성 실패입니다.

### 4.2 MariaDB 연결 확인 (Q-IM 전용)

```bash
# MariaDB 컨테이너 연결 확인
docker exec -it idem-mariadb mariadb -u qim -pqim qim -e "SHOW TABLES;"
```

**Q-IM Flyway 마이그레이션 후 정상 출력:**

```
+---------------------------+
| Tables_in_qim             |
+---------------------------+
| auth_mean_mapping         |
| crypto_key_version        |  ← v1.1.0 신규 (CiCryptoService CI 암호화 키 버전)
| flyway_schema_history     |
| last_event_version        |
| outbox                    |
| processed_event           |
| qim_user                  |
| snapshot_meta             |
| user_profile              |
| user_status_history       |  ← v1.1.0 신규 (UserRegistrationService 상태 변경 이력)
+---------------------------+
```

> ⚠️ `flyway_schema_history` 테이블에 V1, V2, **V3** 마이그레이션이 모두 `Success` 상태이어야 합니다.

```bash
# MariaDB Flyway 이력 확인
docker exec -it idem-mariadb mariadb -u qim -pqim qim \
  -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
# 정상 출력:
# | 1 | Initial Q-IM schema     | 1 |
# | 2 | Add idempotent consumer | 1 |
# | 3 | Add CI encryption ...   | 1 |  ← v1.1.0
```

### 4.3 PostgreSQL 연결 확인

```bash
# Docker 컨테이너 내부에서 psql 실행
docker exec -it idem-postgres psql -U onepass -d onepass -c "\dn"
```

**정상 출력 (Q-IM 제거, MariaDB로 이관됨):**

```
      List of schemas
    Name    |  Owner
------------+---------
 agency_stub | onepass
 ido         | onepass
 keycloak    | onepass
 public      | pg_database_owner
 qsign       | onepass
(5 rows)
```

> ℹ️ `qim` 스키마는 PostgreSQL에 없습니다. Q-IM은 MariaDB(`idem-mariadb:3306/qim`)를 사용합니다.

### 4.4 Redis 연결 확인

```bash
docker exec -it idem-redis redis-cli ping
# PONG 이 출력되면 정상
```

### 4.5 Kafka 토픽 생성 확인

```bash
docker exec -it idem-kafka \
  kafka-topics --bootstrap-server localhost:9092 --list
```

**정상 출력 (11개 토픽):**

```
idem.hub.handoff.events
idem.hub.handoff.events.dlq
platform.audit.log
platform.session.advisory
platform.session.advisory.dlq
idem.registry.user.events
idem.registry.user.events.dlq
idem.registry.user.snapshot
idem.gate.auth.events
idem.gate.auth.events.dlq
```

> ⚠️ 토픽 목록이 비어 있거나 일부만 있는 경우 → [11.4 Kafka 오류 해결](#114-kafka--zookeeper-관련-오류) 참조

### 4.6 모니터링 UI 접속 확인

| 서비스 | URL | 계정 | 비고 |
|--------|-----|------|------|
| Kafka UI | http://localhost:8090 | admin / admin | 기본 기동 |
| Redis Insight | http://localhost:5540 | 없음 | 기본 기동 |
| pgAdmin 4 | http://localhost:5050 | admin@onepass.local / admin | profile: tools |
| **Adminer (MariaDB)** | **http://localhost:8091** | **qim / qim (DB: qim)** | profile: tools |
| **Prometheus** | **http://localhost:9090** | **없음** | **profile: monitoring** |
| **Grafana** | **http://localhost:3002** | **admin / admin** | **profile: monitoring** |
| **Loki** | **http://localhost:3100** | **없음** | **profile: monitoring (내부)** |

### 4.7 Monitoring 스택 기동 확인 (profile: monitoring 사용 시)

```bash
# Prometheus 정상 확인
curl -s http://localhost:9090/-/healthy
# Prometheus Server is Healthy.

# Grafana 정상 확인
curl -s http://localhost:3002/api/health | python3 -m json.tool
# { "commit": "...", "database": "ok", "version": "10.4.2" }

# Loki 정상 확인
curl -s http://localhost:3100/ready
# ready

# Prometheus 스크레이프 타겟 확인 (Spring Boot 서비스 기동 후)
curl -s 'http://localhost:9090/api/v1/targets' | python3 -m json.tool | grep '"health"'
# "health": "up"  (ido, q-im, q-sign, agency-stub 각 1개씩)
```

> 💡 Grafana 접속 후 **Dashboards → Browse** 에서 자동 프로비저닝된 데이터소스 확인:
> - **Prometheus** (기본 데이터소스) — Spring Boot Actuator `/actuator/prometheus` scrape
> - **Loki** — Promtail이 Docker 컨테이너 로그를 JSON 파싱해 `correlationId` 레이블 추출

---

## 5. Step 3 — Java 백엔드 빌드

### 5.1 전체 백엔드 빌드

인프라가 모두 `healthy` 상태인 것을 확인한 후 실행합니다.

#### Linux / macOS / Git Bash

```bash
# 프로젝트 루트에서 실행
# 테스트 제외 빌드 (권장 — 로컬 개발 시)
./gradlew :idem-common:build \
          :idem-gate:build \
          :idem-registry:build \
          :idem-hub:build \
          :idem-tenant-sample:build \
          -x test
```

#### Windows (PowerShell)

```powershell
.\gradlew.bat :idem-common:build `
              :idem-gate:build `
              :idem-registry:build `
              :idem-hub:build `
              :idem-tenant-sample:build `
              -x test
```

#### Windows (CMD)

```cmd
gradlew.bat :idem-common:build :idem-gate:build :idem-registry:build :idem-hub:build :idem-tenant-sample:build -x test
```

### 5.2 빌드 결과 확인

성공 시 각 모듈의 `build/libs/` 폴더에 JAR 파일이 생성됩니다:

```bash
ls -la idem-gate/build/libs/
ls -la idem-registry/build/libs/
ls -la idem-hub/build/libs/
ls -la idem-tenant-sample/build/libs/
```

**예상 출력:**

```
idem-gate/build/libs/q-sign-0.1.0-SNAPSHOT.jar
idem-registry/build/libs/q-im-0.1.0-SNAPSHOT.jar
idem-hub/build/libs/ido-0.1.0-SNAPSHOT.jar
idem-tenant-sample/build/libs/agency-stub-0.1.0-SNAPSHOT.jar
```

### 5.3 빌드 시 주의사항

```bash
# 첫 빌드 시 Gradle이 의존성을 다운로드하므로 수 분 소요됩니다.
# 이후 빌드는 캐시로 빠르게 완료됩니다.

# 빌드 실패 시 클린 빌드:
./gradlew clean build -x test

# 특정 모듈만 빌드:
./gradlew :idem-hub:build -x test

# 빌드 디버그 출력 (오류 상세 확인):
./gradlew :idem-hub:build -x test --stacktrace
```

---

## 6. Step 4 — 백엔드 서비스 기동

> 📋 **기동 순서 중요**: 서비스 간 의존성이 있으므로 아래 순서를 따릅니다.  
>
> ```
> 1. q-sign  (port 8081) — 인증 SoR, 의존성 없음
> 2. q-im    (port 8082) — 식별 SoR, 의존성 없음
> 3. ido     (port 8083) — q-sign, q-im 내부 호출 포함
> 4. agency-stub (port 8084) — 독립적
> ```

각 서비스는 **별도 터미널** 창에서 실행합니다. (4개 터미널 필요)

### 6.1 q-sign 기동 (터미널 1)

#### Linux / macOS / Git Bash

```bash
# 프로젝트 루트에서
./gradlew :idem-gate:bootRun
```

#### Windows PowerShell

```powershell
.\gradlew.bat :idem-gate:bootRun
```

**정상 기동 로그:**

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
...
2026-05-07T10:00:01.000Z  INFO 1234 --- [q-sign] [main] k.g.s.qsign.QSignApplication   : Started QSignApplication in 4.512 seconds
```

**기동 확인:**

```bash
curl http://localhost:8081/actuator/health
# {"status":"UP"}
```

### 6.2 q-im 기동 (터미널 2)

#### Linux / macOS / Git Bash

```bash
./gradlew :idem-registry:bootRun
```

> ⚠️ q-im은 **MariaDB** (`localhost:3306/qim`)에 연결합니다.  
> `idem-mariadb` 컨테이너가 `healthy` 상태인지 먼저 확인하세요.

#### Windows PowerShell

```powershell
.\gradlew.bat :idem-registry:bootRun
```

**기동 확인:**

```bash
curl http://localhost:8082/actuator/health
# {"status":"UP"}
```

### 6.3 ido 기동 (터미널 3)

#### Linux / macOS / Git Bash

```bash
./gradlew :idem-hub:bootRun
```

#### Windows PowerShell

```powershell
.\gradlew.bat :idem-hub:bootRun
```

**기동 확인:**

```bash
curl http://localhost:8083/actuator/health
# {"status":"UP","components":{"db":{"status":"UP"},"kafka":{"status":"UP"},"redis":{"status":"UP"}}}
```

### 6.4 agency-stub 기동 (터미널 4)

#### Linux / macOS / Git Bash

```bash
./gradlew :idem-tenant-sample:bootRun
```

#### Windows PowerShell

```powershell
.\gradlew.bat :idem-tenant-sample:bootRun
```

**기동 확인:**

```bash
curl http://localhost:8084/actuator/health
# {"status":"UP"}
```

### 6.5 환경변수로 기동 (선택)

기본값이 이미 `application.yml`에 설정되어 있어 별도 환경변수 없이 로컬에서 바로 실행됩니다.  
환경을 변경해야 할 경우:

#### Linux / macOS

```bash
# IdO / Q-Sign / agency-stub 환경변수 재정의 예시 (PostgreSQL 공용)
REDIS_HOST=localhost \
DB_HOST=localhost \
KAFKA_SERVERS=localhost:9092 \
./gradlew :idem-hub:bootRun

# Q-IM 환경변수 재정의 예시 (MariaDB 전용 — IDEM_REGISTRY_DB_* 네임스페이스)
IDEM_REGISTRY_DB_HOST=localhost \
IDEM_REGISTRY_DB_PORT=3306 \
IDEM_REGISTRY_DB_NAME=qim \
IDEM_REGISTRY_DB_USERNAME=qim \
IDEM_REGISTRY_DB_PASSWORD=qim \
REDIS_HOST=localhost \
KAFKA_SERVERS=localhost:9092 \
./gradlew :idem-registry:bootRun
```

#### Windows PowerShell

```powershell
# IdO / Q-Sign / agency-stub
$env:REDIS_HOST="localhost"
$env:DB_HOST="localhost"
$env:KAFKA_SERVERS="localhost:9092"
.\gradlew.bat :idem-hub:bootRun

# Q-IM (MariaDB 전용)
$env:IDEM_REGISTRY_DB_HOST="localhost"
$env:IDEM_REGISTRY_DB_PORT="3306"
$env:IDEM_REGISTRY_DB_NAME="qim"
$env:IDEM_REGISTRY_DB_USERNAME="qim"
$env:IDEM_REGISTRY_DB_PASSWORD="qim"
.\gradlew.bat :idem-registry:bootRun
```

#### Windows Git Bash

```bash
# IdO
REDIS_HOST=localhost DB_HOST=localhost ./gradlew :idem-hub:bootRun
# Q-IM
IDEM_REGISTRY_DB_HOST=localhost IDEM_REGISTRY_DB_PORT=3306 IDEM_REGISTRY_DB_NAME=qim IDEM_REGISTRY_DB_USERNAME=qim IDEM_REGISTRY_DB_PASSWORD=qim ./gradlew :idem-registry:bootRun
```

### 6.6 Spring Boot 브로커 모드 설정 (ido)

기본값은 `qsign` 모드입니다. Keycloak 모드로 전환 시:

```bash
# Keycloak 모드 (Keycloak 컨테이너 별도 기동 필요)
IDEM_HUB_BROKER_MODE=keycloak ./gradlew :idem-hub:bootRun
```

---

## 7. Step 5 — 프론트엔드 기동

프론트엔드는 **순수 React SPA**입니다. Spring Boot가 포함되어 있지 않습니다.  
React 개발 서버는 `port 3000`으로 실행되며, `/api/**` 요청을 자동으로 `ido(:8083)`로 프록시합니다.

### 7.1 방법 A: 직접 Yarn 사용 (권장)

**터미널 5 (새 터미널)**에서 실행합니다.

```bash
# idem-console/frontend 디렉토리로 이동
cd idem-console/frontend

# 의존성 설치 (최초 1회 또는 package.json 변경 시)
yarn install

# 개발 서버 기동 (port 3000)
yarn dev
```

#### Windows PowerShell

```powershell
cd idem-console\frontend
yarn install
yarn dev
```

**정상 기동 로그:**

```
<i> [webpack-dev-server] Project is running at:
<i> [webpack-dev-server] Loopback: http://localhost:3000/
<i> [webpack-dev-server] On Your Network (IPv4): http://192.168.x.x:3000/
webpack compiled successfully
```

브라우저에서 **http://localhost:3000** 접속.

### 7.2 방법 B: Gradle 태스크 사용

프로젝트 루트에서 실행합니다.

```bash
# 의존성 설치 + 개발 서버 기동 (한 번에)
./gradlew :idem-console:frontendDev
```

> 💡 `frontendDev` 태스크는 내부적으로 `yarn install` → `yarn dev`를 순서대로 실행합니다.

### 7.3 프론트엔드 프로덕션 빌드 (선택)

```bash
cd idem-console/frontend
yarn build:prod
# dist/ 폴더에 빌드 산출물 생성
```

---

## 8. Step 6 — 전체 동작 확인

### 8.1 전체 서비스 헬스체크 스크립트

아래 스크립트를 `check-health.sh` 로 저장하거나 직접 실행합니다.

#### Linux / macOS / Git Bash

```bash
#!/bin/bash
echo "=== OnePass 서비스 상태 확인 ==="

check() {
  local name=$1
  local url=$2
  if curl -sf "$url" > /dev/null 2>&1; then
    echo "✅ $name — OK ($url)"
  else
    echo "❌ $name — FAIL ($url)"
  fi
}

check "q-sign    (8081)" "http://localhost:8081/actuator/health"
check "q-im      (8082)" "http://localhost:8082/actuator/health"
check "ido       (8083)" "http://localhost:8083/actuator/health"
check "agency-stub(8084)" "http://localhost:8084/actuator/health"
check "React SPA (3000)" "http://localhost:3000"
check "Kafka UI  (8090)" "http://localhost:8090"
check "Redis Insight(5540)" "http://localhost:5540"
```

#### Windows PowerShell

```powershell
$services = @(
  @{Name="q-sign    (8081)"; Url="http://localhost:8081/actuator/health"},
  @{Name="q-im      (8082)"; Url="http://localhost:8082/actuator/health"},
  @{Name="ido       (8083)"; Url="http://localhost:8083/actuator/health"},
  @{Name="agency-stub(8084)"; Url="http://localhost:8084/actuator/health"},
  @{Name="React SPA (3000)"; Url="http://localhost:3000"},
  @{Name="Kafka UI  (8090)"; Url="http://localhost:8090"}
)

foreach ($s in $services) {
  try {
    Invoke-WebRequest -Uri $s.Url -UseBasicParsing -TimeoutSec 3 | Out-Null
    Write-Host "✅ $($s.Name) — OK"
  } catch {
    Write-Host "❌ $($s.Name) — FAIL"
  }
}
```

### 8.2 PoC 흐름 기본 테스트

```bash
# 1. q-sign — 인증 브로커 URL 생성 (KAKAO OIDC)
curl -v "http://localhost:8083/api/v1/broker/kakao/authorize?returnUrl=http://localhost:8084&requestedLevel=L1"
# 기대 결과: 302 Redirect → Kakao 인증 URL

# 2. q-im — 사용자 조회 (등록된 사용자가 없으면 404)
curl http://localhost:8082/api/v1/users/by-hash?identifierHash=test123

# 3. ido — FE 세션 상태 확인
curl http://localhost:8083/api/v1/fe-session/check \
  -H "Cookie: feSessionId=invalid"
# 기대 결과: 401 또는 세션 없음 응답

# 4. agency-stub — 기관 헬스체크
curl http://localhost:8084/actuator/health
```

### 8.3 v1.8.0 신규 기능 동작 확인

#### Admin API — 기관 관리

관리 API 는 관리자 세션(비밀번호 + 2단계 TOTP) 뒤에 있다(S7, `docs/admin-auth.md`). 로컬 hub 는 `IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD` 로 첫 관리자 `admin` 을 만든다.
`scripts/lib/admin-login.sh` 가 로그인(첫 로그인이면 2단계 등록·비밀번호 변경까지)해 세션 쿠키 값을 돌려준다.

```bash
export IDEM_ADMIN_PASSWORD='…' IDEM_ADMIN_NEW_PASSWORD='…' IDEM_ADMIN_TOTP_SECRET_FILE=~/.idem/admin-totp-secret
SID=$(scripts/lib/admin-login.sh)
ADM=(-H "Cookie: idemAdminSid=$SID" -H 'X-Requested-With: dev')     # 쓰기 요청은 X-Requested-With 가 없으면 403

# 기관 목록 조회
curl -s http://localhost:8083/api/v1/admin/agencies "${ADM[@]}" | python3 -m json.tool

# 기관 상세 조회 (agency-stub 기본 기관코드 사용)
# (D3) 운영 마이그레이션에는 시드 기관이 없다 — 먼저 scripts/dev/seed-dev-agencies.sh 로 AGENCY_STUB_001 을 만들고 출력된 API 키를 쓴다
curl -s http://localhost:8083/api/v1/admin/agencies/AGENCY_STUB_001 "${ADM[@]}" | python3 -m json.tool

# 기관 통계 조회
curl -s http://localhost:8083/api/v1/admin/agencies/AGENCY_STUB_001/stats "${ADM[@]}" | python3 -m json.tool

# API 키 로테이션 (새 키 발급)
curl -s -X POST http://localhost:8083/api/v1/admin/agencies/AGENCY_STUB_001/rotate-key "${ADM[@]}" \
  -H "Content-Type: application/json" | python3 -m json.tool
```

> 종전 `X-Admin-Id` 헤더는 아무 효력이 없다(무인증 → `401 E-IDO-130`).

#### Rate Limiter — Redis 상태 확인

```bash
# Rate Limiter Redis 키 확인 (Redis CLI)
docker exec -it idem-redis redis-cli

# TPS 카운터 키 확인 (에포크 초 단위 슬라이딩 윈도우)
KEYS idem:rl:tps:*

# 일별 쿼터 카운터 키 확인
KEYS idem:rl:daily:*

# 특정 기관의 현재 TPS 확인 (예: AGENCY_STUB_001, 현재 에포크 초 대입)
# GET ido:rl:tps:AGENCY_STUB_001:<epochSecond>

# per-agency Rate Limit 설정 확인 (PostgreSQL)
docker exec -it idem-postgres psql -U onepass -d onepass \
  -c "SELECT * FROM ido.agency_rate_limit_config;"
```

#### PKCE — Q-Sign PKCE 흐름 테스트

```bash
# PKCE가 활성화되어 있는지 application.yml 확인
grep -A 3 "pkce" idem-gate/src/main/resources/application.yml
# idem.gate.pkce.enabled: true 이어야 함

# Redis에서 PKCE challenge 키 확인 (인증 흐름 진행 중일 때)
docker exec -it idem-redis redis-cli KEYS "idem:gate:pkce:challenge:*"

# PKCE challenge TTL 확인
# docker exec -it idem-redis redis-cli TTL "idem:gate:pkce:challenge:<state>"
```

#### MemberLookup — CI 기반 회원 조회 (IdO → Q-IM)

```bash
# CI(암호화된 개인식별정보) 기반 회원 조회
# 실제 CI는 암호화된 값이므로 테스트 시 더미 값 사용 (404 응답 기대)
curl -s -X POST http://localhost:8083/api/v1/member/lookup \
  -H "Content-Type: application/json" \
  -H "X-Agency-Code: AGENCY_STUB_001" \
  -H "X-Correlation-Id: test-corr-001" \
  -d '{"encryptedCi": "dGVzdC1jaS12YWx1ZQ=="}' | python3 -m json.tool
# 기대 결과: 유효하지 않은 CI → 404 또는 복호화 오류 응답

# Q-IM MemberLookup 로그 확인 (member_lookup_log 테이블)
docker exec -it idem-mariadb mariadb -u qim -pqim qim \
  -e "SELECT agency_code, lookup_type, result_code, response_ms, occurred_at \
      FROM member_lookup_log ORDER BY occurred_at DESC LIMIT 10;"
```

#### AES 키 로테이션 스케줄러 — Redis 상태 확인

```bash
# 현재 활성 AES 키 버전 확인
docker exec -it idem-redis redis-cli GET "idem:crypto:aes:current-version"
# 예시 출력: "v1"

# 키 버전별 등록 여부 확인
docker exec -it idem-redis redis-cli KEYS "idem:crypto:aes:version:*"

# 로테이션 분산 락 확인 (로테이션 진행 중에만 존재)
docker exec -it idem-redis redis-cli EXISTS "idem:crypto:aes:rotate-lock"

# PostgreSQL crypto_key_registry 테이블 확인
docker exec -it idem-postgres psql -U onepass -d onepass \
  -c "SELECT key_type, key_version, active, current_flag, grace_until \
      FROM ido.crypto_key_registry ORDER BY created_at;"
```

---

### 8.4 v1.9.x 신규 기능 동작 확인 (★ v1.9.0~v1.9.3)

#### 기관 이벤트 폴링 API (v1.9.3)

```bash
# 기관 이벤트 폴링 — agency-stub 기관키로 직접 호출
curl -s "http://localhost:8083/api/v1/agency/events?limit=10" \
  -H "X-Agency-Code: AGENCY_STUB_001" \
  -H "X-Agency-Key: stub-api-key-dev-001" | python3 -m json.tool
# 기대 결과: { "events": [...], "count": N, "hasMore": false, "polledAt": "..." }

# since 커서를 사용한 연속 폴링
curl -s "http://localhost:8083/api/v1/agency/events?since=2026-05-09T00:00:00Z&limit=5" \
  -H "X-Agency-Code: AGENCY_STUB_001" \
  -H "X-Agency-Key: stub-api-key-dev-001" | python3 -m json.tool

# 이벤트 읽음 처리 (dispatchId는 위 응답에서 확인)
curl -s -X POST "http://localhost:8083/api/v1/agency/events/{dispatchId}/read" \
  -H "X-Agency-Code: AGENCY_STUB_001" \
  -H "X-Agency-Key: stub-api-key-dev-001"
# 기대 결과: HTTP 204 No Content

# webhook_dispatch_outbox 테이블 직접 확인
docker exec -it idem-postgres psql -U onepass -d onepass \
  -c "SELECT dispatch_id, event_type, status, agency_code, created_at \
      FROM ido.webhook_dispatch_outbox \
      ORDER BY created_at DESC LIMIT 10;"
```

#### Provider 라우팅 확인 (v1.9.0)

```bash
# provider_config 테이블에서 provider_type 확인
docker exec -it idem-postgres psql -U onepass -d onepass \
  -c "SELECT provider_code, provider_type, broker_mode, active \
      FROM ido.provider_config ORDER BY provider_code;"

# provider_circuit_config 테이블 확인 (동적 CB 설정)
docker exec -it idem-postgres psql -U onepass -d onepass \
  -c "SELECT provider_code, sliding_window_size, failure_rate_threshold, \
             wait_duration_open_ms, active \
      FROM ido.provider_circuit_config;"

# Redis에서 Provider 설정 캐시 확인
docker exec -it idem-redis redis-cli KEYS "idem:provider-config:*"
```

#### Broker Audit Log 확인 (v1.9.0)

```bash
# 브로커 감사 로그 최근 20건 확인
docker exec -it idem-postgres psql -U onepass -d onepass \
  -c "SELECT provider_code, provider_type, action, auth_level, \
             error_code, created_at \
      FROM ido.broker_audit_log \
      ORDER BY created_at DESC LIMIT 20;"

# 특정 provider_code의 실패 내역 확인
docker exec -it idem-postgres psql -U onepass -d onepass \
  -c "SELECT action, error_code, error_detail, client_ip, created_at \
      FROM ido.broker_audit_log \
      WHERE provider_code = 'KAKAO_OIDC' AND action = 'FAIL' \
      ORDER BY created_at DESC LIMIT 10;"
```

#### auth_result V10 확장 컬럼 확인

```bash
# IdO auth_result V10 확장 컬럼 확인
docker exec -it idem-postgres psql -U onepass -d onepass \
  -c "SELECT auth_result_id, provider_code, auth_method, \
             issued_at, expires_at,
             CASE WHEN raw_id_token IS NOT NULL THEN 'SET' ELSE 'NULL' END AS raw_token_status,
             created_at \
      FROM ido.auth_result \
      ORDER BY created_at DESC LIMIT 5;"

# Q-Sign auth_method 컬럼 확인
docker exec -it idem-postgres psql -U onepass -d onepass \
  -c "SELECT auth_result_id, provider_code, auth_method, created_at \
      FROM qsign.auth_result \
      ORDER BY created_at DESC LIMIT 5;"
```

---

## 9. 서비스 포트 및 접속 URL 정리

| 서비스 | URL | 계정 | 용도 |
|--------|-----|------|------|
| **React SPA (개발)** | http://localhost:3000 | — | 프론트엔드 HMR 개발서버 |
| **React SPA (Nginx)** | http://localhost:3001 | — | 프로덕션 Nginx 서빙 (profile: optionB) |
| **q-sign** | http://localhost:8081 | — | 인증 SoR API |
| **q-im** | http://localhost:8082 | — | 식별 SoR API |
| **ido (BFF)** | http://localhost:8083 | — | 정책 오케스트레이터 + FE BFF |
| **agency-stub** | http://localhost:8084 | — | 기관 로컬 세션 시뮬레이터 |
| **Keycloak** | http://localhost:8088 | admin / admin | OIDC 브로커 (profile: keycloak) |
| **Kafka UI** | http://localhost:8090 | admin / admin | Kafka 토픽/메시지 모니터링 |
| **Redis Insight** | http://localhost:5540 | — | Redis 키/데이터 뷰어 |
| **pgAdmin 4** | http://localhost:5050 | admin@onepass.local / admin | PostgreSQL DB GUI (profile: tools) |
| **Adminer** | http://localhost:8091 | qim / qim (DB: qim) | **MariaDB(Q-IM) 관리 UI** (profile: tools) |
| **Schema Registry** | http://localhost:8085 | — | Avro 스키마 관리 (profile: schema) |
| **Prometheus** | http://localhost:9090 | — | 메트릭 수집 (profile: monitoring) |
| **Grafana** | http://localhost:3002 | admin / admin | 대시보드 (profile: monitoring) |
| **Loki** | http://localhost:3100 | — | 로그 수집 (profile: monitoring) |
| **PostgreSQL** | localhost:5432 | onepass / onepass | Q-Sign / IdO / agency-stub / Keycloak DB |
| **MariaDB** | localhost:3306 | qim / qim (DB: qim) | **Q-IM 전용 DB** |
| **Redis** | localhost:6379 | 없음 | Redis 직접 접속 |
| **Kafka** | localhost:9092 | — | Kafka 외부 리스너 |
| **Zookeeper** | localhost:2181 | — | Zookeeper |

### DB 엔진별 스키마 구조

**PostgreSQL** (`localhost:5432`, DB: `onepass`)
```
DB명: onepass
├── qsign.*        — Q-Sign 인증 SoR 테이블
├── ido.*          — IdO 정책·Handoff·FE세션 테이블
│   ├── agency_meta              — 기관 메타/정책 (whitelist, auth level 등)
│   ├── handoff_ticket           — Handoff 티켓 (TTL 60초)
│   ├── fe_session               — FE 세션
│   ├── audit_log                — 감사 로그 (2년 보존)
│   ├── idempotency_key          — 멱등 처리 키
│   ├── crypto_key_registry      ← ★ V9 신규 AES/HMAC 키 버전 레지스트리
│   ├── agency_rate_limit_config ← ★ V9 신규 기관별 Rate Limit 설정
│   ├── agency_meta_history      ← ★ V9 신규 기관 설정 변경 이력 (JSONB)
│   └── member_lookup_log        ← ★ V9 신규 CI/Hash 회원조회 감사 로그
├── agency_stub.*  — Agency-Stub 테이블
└── keycloak.*     — Keycloak 테이블 (keycloak 프로파일 사용 시)
```

> 💡 **IdO V9 신규 테이블 요약**
>
> | 테이블 | 용도 | 주요 컬럼 |
> |--------|------|----------|
> | `crypto_key_registry` | AES/HMAC 키 버전 관리, grace period 지원 | `key_type`, `key_version`, `active`, `current_flag`, `grace_until` |
> | `agency_rate_limit_config` | 기관별 TPS/일별 쿼터 오버라이드 | `tps_limit`(기본 200), `daily_limit`(기본 1,000,000), `burst_multiplier` |
> | `agency_meta_history` | 기관 설정 변경 이력 감사 | `policy_version`, `changed_by`, `change_reason`, `change_detail (JSONB)` |
> | `member_lookup_log` | CI/Hash 기반 조회 감사 (GDPR 준수) | `lookup_type`, `result_code`, `qim_user_id`, `response_ms` |

**MariaDB** (`localhost:3306`, DB: `qim`) — Q-IM 전용
```
DB명: qim
├── qim_user            — 사용자 오브젝트 SoR (§10.2)
├── auth_mean_mapping   — identifierHash → qimUserId 단방향 매핑 (§10.3)
├── user_profile        — 사용자 속성 (PII 마스킹, §10.4)
├── user_status_history — 상태 전이 감사 이력 (§10.5)     ← ★ V3 신규
├── crypto_key_version  — CI 암호화 AES 키 버전 관리       ← ★ V3 신규
├── outbox              — Transactional Outbox (§10.5.2)
├── last_event_version  — Ordered Consumer 버전 추적 (§11.5.5)
├── processed_event     — 멱등 컨슈머 중복 방지 (§16.3)
└── snapshot_meta       — Compacted Snapshot 발행 이력 (§11.5.6)
```

> ℹ️ Q-IM 테이블은 Flyway(`idem-registry/src/main/resources/db/migration/`)가 자동 생성합니다.  
> PostgreSQL `init-db.sql`에 Q-IM 스키마는 포함되지 않습니다.

---

## 10. IDE 설정 가이드

### 10.1 IntelliJ IDEA 설정 (권장)

#### A. 프로젝트 열기

1. **File → Open** → 프로젝트 루트 폴더 선택
2. "Open as Gradle Project" 클릭
3. **Gradle 동기화** 자동 실행 (수 분 소요)

#### B. JDK 설정

1. **File → Project Structure** (Ctrl+Alt+Shift+S / ⌘+;)
2. **Project → SDK**: `Eclipse Temurin 21` 선택
   - 없으면 **Add SDK → Download JDK** → `Eclipse Temurin 21` 다운로드
3. **Language level**: `21 - ...` 선택
4. **Apply → OK**

#### C. Gradle 설정

1. **File → Settings** (Ctrl+Alt+S) / **Preferences** (macOS)
2. **Build, Execution, Deployment → Build Tools → Gradle**
3. **Gradle JVM**: `Project SDK (21)` 선택
4. **Build and run using**: `Gradle` 선택
5. **Apply → OK**

#### D. Lombok 플러그인 설치

1. **File → Settings → Plugins**
2. `Lombok` 검색 → **Install**
3. IDE 재시작

#### E. 어노테이션 프로세서 활성화

1. **File → Settings → Build → Compiler → Annotation Processors**
2. **Enable annotation processing** 체크
3. **Apply**

#### F. 각 서비스 실행 설정 (Run Configuration)

1. **Run → Edit Configurations**
2. **+** 버튼 → **Gradle**
3. 각 서비스별 설정:

| Name | Gradle project | Tasks |
|------|--------------|-------|
| q-sign | `integration-sso:q-sign` | `bootRun` |
| q-im | `integration-sso:q-im` | `bootRun` |
| ido | `integration-sso:ido` | `bootRun` |
| agency-stub | `integration-sso:agency-stub` | `bootRun` |

---

### 10.2 VS Code 설정 (프론트엔드 개발)

#### A. 권장 확장 설치

```json
// .vscode/extensions.json 에 추가 가능
{
  "recommendations": [
    "dbaeumer.vscode-eslint",
    "esbenp.prettier-vscode",
    "bradlc.vscode-tailwindcss",
    "ms-vscode.vscode-typescript-next",
    "ms-azuretools.vscode-docker"
  ]
}
```

#### B. 백엔드 Java 개발 (VS Code)

확장 설치:
- **Extension Pack for Java** (Microsoft)
- **Spring Boot Extension Pack** (VMware)
- **Lombok Annotations Support for VS Code**

---

## 11. 자주 발생하는 오류 및 해결 방법

---

### 11.1 Docker 관련 오류

---

#### 오류: `Cannot connect to the Docker daemon`

**증상:**
```
ERROR: Cannot connect to the Docker daemon at unix:///var/run/docker.sock.
Is the docker daemon running?
```

**원인 및 해결:**

**Linux:**
```bash
# Docker 서비스 상태 확인
sudo systemctl status docker

# Docker 서비스 시작
sudo systemctl start docker

# 현재 사용자가 docker 그룹에 있는지 확인
groups $USER
# docker 그룹이 없으면:
sudo usermod -aG docker $USER
# 로그아웃 후 재로그인 또는:
newgrp docker
```

**macOS:**
```bash
# Docker Desktop이 실행 중인지 확인 (메뉴바 고래 아이콘)
# 실행이 안 되어 있으면 Applications에서 Docker.app 실행
open -a Docker
# 잠시 기다린 후 재시도
```

**Windows:**
```powershell
# Docker Desktop 앱이 실행 중인지 확인 (시스템 트레이 고래 아이콘)
# WSL 2 상태 확인
wsl --status
# WSL 2 업데이트
wsl --update
```

---

#### 오류: `docker compose` 명령어를 찾을 수 없음

**증상:**
```
docker: 'compose' is not a docker command.
# 또는
bash: docker-compose: command not found
```

**원인 및 해결:**

이 프로젝트는 **Docker Compose v2** (`docker compose`, 하이픈 없음)를 사용합니다.

```bash
# 버전 확인
docker compose version
# Docker Compose version v2.x.x   ← 이렇게 나와야 정상

# v1 (docker-compose)이 설치된 경우 v2로 업그레이드:
# Linux
sudo apt-get remove docker-compose
# Docker Desktop 재설치 또는 Compose Plugin 설치:
sudo apt-get install docker-compose-plugin
```

---

#### 오류: `Bind for 0.0.0.0:5432 failed: port is already allocated`

**증상:**
```
Error response from daemon: driver failed programming external connectivity...
Bind for 0.0.0.0:5432 failed: port is already allocated
```

**원인**: 로컬에 PostgreSQL이 이미 설치·실행 중이어서 포트가 충돌합니다.

**해결:**

```bash
# 로컬 PostgreSQL 서비스 중지

# Linux
sudo systemctl stop postgresql

# macOS (Homebrew)
brew services stop postgresql@16
# 또는
pg_ctl -D /usr/local/var/postgresql@16 stop

# Windows
net stop postgresql-x64-16
# 또는 서비스 관리자(services.msc)에서 PostgreSQL 서비스 중지
```

Redis 포트(6379) 충돌 시:
```bash
# Linux/macOS
sudo systemctl stop redis

# macOS Homebrew
brew services stop redis

# Windows
net stop Redis
```

---

#### 오류: `no space left on device`

**증상:**
```
failed to create shim task: ... no space left on device
```

**해결:**
```bash
# 사용하지 않는 Docker 리소스 정리
docker system prune -a --volumes

# 특정 볼륨만 삭제 (데이터 초기화 포함)
docker compose -f infra/docker/docker-compose.yml down -v
```

> ⚠️ `-v` 옵션은 **MariaDB, PostgreSQL, Kafka, Redis 데이터를 모두 삭제**합니다. 주의!

---

### 11.2 MariaDB 관련 오류 (Q-IM 전용)

---

#### 오류: Q-IM Flyway 마이그레이션 실패 — `Unable to obtain connection`

**증상:**
```
org.flywaydb.core.api.exception.FlywayException:
  Unable to obtain connection from database:
  Communications link failure
```

**원인**: MariaDB 컨테이너가 아직 준비되지 않았습니다.

**해결:**
```bash
# MariaDB 컨테이너 상태 확인
docker compose -f infra/docker/docker-compose.yml ps mariadb
# STATUS 컬럼이 "Up (healthy)" 인지 확인

# healthy 상태가 아니면 로그 확인
docker compose -f infra/docker/docker-compose.yml logs mariadb

# MariaDB 재시작
docker compose -f infra/docker/docker-compose.yml restart mariadb

# 준비 완료까지 대기 후 Q-IM 재기동
./gradlew :idem-registry:bootRun
```

---

#### 오류: `Access denied for user 'qim'@'...'`

**증상:**
```
java.sql.SQLException: Access denied for user 'qim'@'172.20.0.22' (using password: YES)
```

**원인**: MariaDB 컨테이너 볼륨이 손상되거나 사용자 설정이 누락되었습니다.

**해결:**
```bash
# MariaDB 볼륨 초기화 (데이터 삭제 주의)
docker compose -f infra/docker/docker-compose.yml down -v mariadb
docker compose -f infra/docker/docker-compose.yml up -d mariadb
# healthy 상태 확인 후 q-im 재기동
```

---

#### 오류: `Bind for 0.0.0.0:3306 failed: port is already allocated`

**원인**: 로컬에 MySQL/MariaDB가 이미 실행 중입니다.

**해결:**
```bash
# Linux
sudo systemctl stop mysql
sudo systemctl stop mariadb

# macOS (Homebrew)
brew services stop mysql
brew services stop mariadb

# Windows
net stop MySQL80
```

---

### 11.3 PostgreSQL 관련 오류

---

#### 오류: Flyway 마이그레이션 실패 — `Unable to obtain connection`

**증상:**
```
org.flywaydb.core.api.exception.FlywayException:
  Unable to obtain connection from database:
  Connection refused. Check that the hostname and port are correct
  and that the postmaster is accepting TCP/IP connections.
```

**원인**: PostgreSQL 컨테이너가 아직 준비되지 않았거나 포트가 다릅니다.

**해결:**
```bash
# PostgreSQL 컨테이너 상태 확인
docker compose -f infra/docker/docker-compose.yml ps postgres
# STATUS 컬럼이 "Up (healthy)" 인지 확인

# healthy 상태가 아니면 로그 확인
docker compose -f infra/docker/docker-compose.yml logs postgres

# postgres 컨테이너 재시작
docker compose -f infra/docker/docker-compose.yml restart postgres

# 준비 완료까지 대기 후 Spring Boot 재기동
./gradlew :idem-hub:bootRun
```

---

#### 오류: `FATAL: role "onepass" does not exist`

**증상:**
```
FATAL: role "onepass" does not exist
```

**원인**: `init-db.sql`이 실행되지 않아 스키마/유저가 없습니다.

**해결:**
```bash
# PostgreSQL 컨테이너 완전 초기화 (볼륨 삭제)
docker compose -f infra/docker/docker-compose.yml down -v postgres
docker compose -f infra/docker/docker-compose.yml up -d postgres

# 또는 직접 초기화 스크립트 실행
docker exec -i idem-postgres \
  psql -U onepass -d onepass < infra/docker/init-db.sql
```

---

#### 오류: `ERROR: schema "qsign" already exists`

**증상**: Flyway 마이그레이션에서 스키마 중복 오류 발생

**해결:**
```bash
# Flyway baseline 재설정 (마이그레이션 기록 테이블 초기화)
docker exec -it idem-postgres psql -U onepass -d onepass -c \
  "DELETE FROM ido.flyway_schema_history WHERE version = '1';"

# 또는 완전 초기화 (DB 볼륨 삭제)
docker compose -f infra/docker/docker-compose.yml down -v
docker compose -f infra/docker/docker-compose.yml up -d
```

---

### 11.4 Kafka / Zookeeper 관련 오류

---

#### 오류: `kafka-init` 컨테이너가 exit code 1로 종료

**증상:**
```
docker compose ps 에서 kafka-init: Exited (1)
```

**해결:**
```bash
# kafka-init 로그 확인
docker compose -f infra/docker/docker-compose.yml logs kafka-init

# Kafka가 완전히 기동된 후 kafka-init 재실행
docker compose -f infra/docker/docker-compose.yml restart kafka-init

# 또는 수동으로 토픽 생성 스크립트 실행
docker exec -it idem-kafka bash /create-topics.sh
# 위가 안 되면:
docker exec -it idem-kafka \
  kafka-topics --bootstrap-server localhost:9092 \
  --create --topic idem.gate.auth.events \
  --partitions 6 --replication-factor 1
```

---

#### 오류: `org.apache.kafka.common.errors.TimeoutException`

**증상:**
```
TimeoutException: Topic idem.gate.auth.events not present in metadata after 60000 ms
# 또는
org.springframework.kafka.KafkaException: Could not start bean 'xxxKafkaListenerContainerFactory'
```

**원인**: Spring Boot가 Kafka에 연결을 시도할 때 Kafka가 아직 준비되지 않았습니다.

**해결:**
```bash
# Kafka 상태 확인
docker compose -f infra/docker/docker-compose.yml ps kafka
# "Up (healthy)" 인지 확인

# Kafka 기동까지 30~60초 대기 후 Spring Boot 재기동
./gradlew :idem-hub:bootRun

# Kafka 브로커 직접 연결 테스트
docker exec -it idem-kafka \
  kafka-broker-api-versions --bootstrap-server localhost:9092
```

---

#### 오류: `LEADER_NOT_AVAILABLE` (Kafka 토픽 생성 직후)

**증상:**
```
WARN [AdminClient clientId=...] Received error ...LEADER_NOT_AVAILABLE for topic...
```

**원인**: 토픽이 방금 생성되어 리더 선출이 완료되지 않은 일시적 상태입니다.

**해결**: 10~15초 대기 후 자동 해소됩니다. 애플리케이션 재시도 로직이 있으므로 대부분 자동 복구됩니다.

---

#### 오류: Zookeeper `Connection refused`

```bash
# Zookeeper 상태 확인
docker compose -f infra/docker/docker-compose.yml logs zookeeper | tail -20

# Zookeeper 재시작 (Kafka도 함께 재시작 필요)
docker compose -f infra/docker/docker-compose.yml restart zookeeper
# Zookeeper healthy 확인 후
docker compose -f infra/docker/docker-compose.yml restart kafka
```

---

### 11.5 Redis 관련 오류

---

#### 오류: `Unable to connect to Redis`

**증상:**
```
org.springframework.data.redis.RedisConnectionFailureException:
  Unable to connect to Redis
```

**해결:**
```bash
# Redis 상태 확인
docker compose -f infra/docker/docker-compose.yml ps redis

# Redis 연결 테스트
docker exec -it idem-redis redis-cli ping
# PONG 이 나와야 정상

# Redis 재시작
docker compose -f infra/docker/docker-compose.yml restart redis

# Redis 설정 파일 오류 확인
docker compose -f infra/docker/docker-compose.yml logs redis
```

---

#### 오류: `OOM command not allowed when used memory > maxmemory`

**증상:**
```
io.lettuce.core.RedisCommandExecutionException: OOM command not allowed when used memory > 'maxmemory'
```

**원인**: Redis 메모리 한도 초과

**해결:**
```bash
# Redis 메모리 사용량 확인
docker exec -it idem-redis redis-cli info memory | grep used_memory_human

# 전체 캐시 삭제 (주의: 모든 세션 및 캐시 데이터 삭제)
docker exec -it idem-redis redis-cli FLUSHALL

# 또는 redis.conf의 maxmemory 값 증가 후 재시작
# infra/docker/redis/redis.conf 에서 maxmemory 512mb → 1024mb 변경
docker compose -f infra/docker/docker-compose.yml restart redis
```

---

### 11.6 Gradle / Java 빌드 오류

---

#### 오류: `Could not find or load main class`

**증상:**
```
Error: Could not find or load main class io.github.hipstermin.idem.hub.IdoApplication
```

**해결:**
```bash
# 클린 빌드
./gradlew clean
./gradlew :idem-hub:build -x test

# Java 버전 확인 (반드시 21이어야 함)
java -version
# openjdk version "21.x.x" 이어야 함

# JAVA_HOME 확인
echo $JAVA_HOME
```

---

#### 오류: `Unsupported class file major version 65`

**증상:**
```
java.lang.UnsupportedClassVersionError: ... has been compiled by a more recent version
of the Java Runtime (class file version 65.0), this version of the Java Runtime only
recognizes class file versions up to 61.0
```

**원인**: JDK 17(class version 61)로 실행하려는데 코드가 JDK 21(class version 65)로 빌드됨.

**해결:**
```bash
# JDK 21 설치 확인
java -version
# "21.x.x" 이어야 함. 17이면 21로 교체 필요

# macOS SDKMAN 사용자
sdk use java 21.0.7-tem

# Windows: JAVA_HOME 환경변수가 JDK 21 경로인지 확인
echo $env:JAVA_HOME
```

---

#### 오류: `Gradle build failed — compilation error`

**증상:**
```
> Task :idem-hub:compileJava FAILED
error: cannot find symbol
```

**해결:**
```bash
# 1. platform-common을 먼저 빌드 (다른 모듈의 의존성)
./gradlew :idem-common:build

# 2. 클린 후 전체 재빌드
./gradlew clean
./gradlew :idem-common:build :idem-gate:build :idem-registry:build :idem-hub:build :idem-tenant-sample:build -x test

# 3. 자세한 오류 출력
./gradlew :idem-hub:build -x test --info 2>&1 | grep -A 5 "error:"
```

---

#### 오류: `Could not resolve` (Gradle 의존성 다운로드 실패)

**증상:**
```
Could not resolve org.springframework.boot:spring-boot-starter-web:3.5.9
```

**원인**: 네트워크 문제 또는 Maven Central 접근 불가

**해결:**
```bash
# Gradle 캐시 삭제 후 재시도
# Linux/macOS
rm -rf ~/.gradle/caches/

# Windows PowerShell
Remove-Item -Recurse -Force "$env:USERPROFILE\.gradle\caches"

# 재시도
./gradlew :idem-hub:build -x test

# 프록시 환경인 경우 gradle.properties에 프록시 설정 추가
# ~/.gradle/gradle.properties 파일에:
# systemProp.http.proxyHost=proxy.example.com
# systemProp.http.proxyPort=8080
# systemProp.https.proxyHost=proxy.example.com
# systemProp.https.proxyPort=8080
```

---

#### 오류: Gradle Wrapper 다운로드 실패

**증상:**
```
Exception in thread "main" java.net.ConnectException: Connection timed out
Downloading https://services.gradle.org/distributions/gradle-9.5.0-bin.zip
```

**해결:**
```bash
# Gradle Wrapper를 수동 다운로드 후 설치
# 1. https://services.gradle.org/distributions/gradle-9.5.0-bin.zip 수동 다운로드
# 2. 아래 경로에 배치:

# Linux/macOS
mkdir -p ~/.gradle/wrapper/dists/gradle-9.5.0-bin/[해시값]/
cp gradle-9.5.0-bin.zip ~/.gradle/wrapper/dists/gradle-9.5.0-bin/[해시값]/

# 또는 Gradle 직접 설치 (SDKMAN 사용 시)
sdk install gradle 9.5
```

---

### 11.7 Spring Boot 기동 오류

---

#### 오류: `Port 8081 is already in use`

**증상:**
```
Web server failed to start. Port 8081 was already in use.
```

**해결:**

```bash
# 포트 사용 중인 프로세스 확인 및 종료

# Linux/macOS
lsof -i :8081
kill -9 [PID]

# 또는 한 번에
kill -9 $(lsof -ti :8081)

# Windows PowerShell
netstat -ano | findstr :8081
taskkill /PID [PID] /F

# Windows — 모든 포트 한꺼번에 확인
netstat -ano | findstr "8081\|8082\|8083\|8084"
```

**각 서비스 포트:**

| 서비스 | 포트 |
|--------|------|
| q-sign | 8081 |
| q-im | 8082 |
| ido | 8083 |
| agency-stub | 8084 |

---

#### 오류: `ApplicationContext` 로드 실패 — Kafka 연결 오류

**증상:**
```
org.apache.kafka.common.errors.TimeoutException: Timed out waiting for a node assignment
```

**해결 순서:**
```bash
# 1. Kafka 컨테이너 상태 확인
docker compose -f infra/docker/docker-compose.yml ps kafka
# "Up (healthy)" 이어야 함

# 2. healthy 상태 아니면 30초 대기
sleep 30

# 3. Kafka 직접 연결 테스트
docker exec -it idem-kafka \
  kafka-broker-api-versions --bootstrap-server localhost:9092

# 4. 모두 정상이면 Spring Boot 재기동
./gradlew :idem-hub:bootRun
```

---

#### 오류: `Caused by: javax.net.ssl.SSLHandshakeException`

**증상**: HTTPS 관련 SSL 오류

**해결:**
```bash
# 로컬 개발 환경에서는 HTTP를 사용하므로 SSL 설정이 필요 없습니다.
# application.yml의 server.servlet.session.cookie.secure: true 설정이
# HTTP 로컬에서 문제를 일으킬 수 있습니다.

# 임시 해결 (로컬 전용):
# application.yml에서 아래 설정을 false로 변경
# server.servlet.session.cookie.secure: false
```

---

### 11.8 Flyway 마이그레이션 오류

---

#### 오류: `FlywayException: Validate failed: Migrations have failed validation`

**증상:**
```
Flyway: Migrations have failed validation.
Migration checksum mismatch for migration version 1
-> Applied to database : 1234567890
-> Resolved locally    : 9876543210
```

**원인**: 이미 적용된 마이그레이션 SQL 파일이 변경되었습니다.

**해결:**
```bash
# 방법 1: Flyway repair (체크섬 재정의)
# application.yml에 아래 추가 (임시)
# spring.flyway.repair-on-migrate: true
# 기동 후 해당 설정 제거

# 방법 2: 마이그레이션 기록 수동 수정 (개발 환경 전용)
docker exec -it idem-postgres psql -U onepass -d onepass -c \
  "UPDATE ido.flyway_schema_history SET checksum = [새_체크섬] WHERE version = '1';"

# 방법 3: 완전 초기화 (데이터 전부 삭제, 개발 환경만)
docker compose -f infra/docker/docker-compose.yml down -v
docker compose -f infra/docker/docker-compose.yml up -d
```

---

#### 오류: `FlywayException: Found non-empty schema(s) "qsign" without schema history table`

**증상:**
```
Found non-empty schema(s) "qsign" without schema history table!
Use baseline() or set baselineOnMigrate to true to initialize the schema history table.
```

**해결:**
```yaml
# application.yml에 아래 추가 (이미 설정되어 있어야 함)
spring:
  flyway:
    baseline-on-migrate: true
    baseline-version: 0
```

이미 설정되어 있다면 DB를 초기화하고 재시도:
```bash
docker compose -f infra/docker/docker-compose.yml down -v
docker compose -f infra/docker/docker-compose.yml up -d
```

---

### 11.9 프론트엔드 (Node.js / Yarn) 오류

---

#### 오류: `node: /lib/x86_64-linux-gnu/libc.so.6: version GLIBC_2.28 not found`

**원인**: Node.js 20이 요구하는 glibc 버전이 없습니다 (구형 Linux).

**해결:**
```bash
# Ubuntu 18.04 이하의 경우 nvm으로 Node.js 18 LTS 사용
nvm install 18
nvm use 18
```

---

#### 오류: `yarn: command not found`

**해결:**
```bash
# npm으로 yarn 설치
npm install -g yarn

# macOS Homebrew
brew install yarn

# 버전 확인
yarn --version
```

---

#### 오류: `ERROR: ENOSPC: System limit for number of file watchers reached`

**증상**: Linux에서 webpack-dev-server 기동 시 발생

**해결:**
```bash
# 파일 감시자 한도 증가
echo fs.inotify.max_user_watches=524288 | sudo tee -a /etc/sysctl.conf
sudo sysctl -p

# 또는 임시 적용
sudo sysctl fs.inotify.max_user_watches=524288
```

---

#### 오류: `EACCES: permission denied, mkdir '/usr/local/lib/node_modules'`

**원인**: Node.js 전역 패키지 설치 권한 없음

**해결:**
```bash
# npm의 전역 설치 디렉토리를 홈 디렉토리로 변경
mkdir -p ~/.npm-global
npm config set prefix '~/.npm-global'
echo 'export PATH=~/.npm-global/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
npm install -g yarn
```

---

#### 오류: `Module not found: Can't resolve '@/components/...'`

**원인**: TypeScript path alias 설정 문제

**해결:**
```bash
# node_modules 삭제 후 재설치
cd idem-console/frontend
rm -rf node_modules
yarn install

# webpack.config.js의 resolve.alias 설정 확인
# tsconfig.json의 paths 설정 확인
```

---

#### 오류: `Proxy error: Could not proxy request /api/...`

**증상**: 프론트엔드에서 API 호출 시 `ECONNREFUSED`

**원인**: `ido` 서버(port 8083)가 실행되지 않음

**해결:**
```bash
# ido 서버 상태 확인
curl http://localhost:8083/actuator/health

# ido 서버 기동 (별도 터미널)
./gradlew :idem-hub:bootRun
```

---

### 11.10 Keycloak 관련 오류

---

#### 오류: `Connection refused` — Keycloak에 연결할 수 없음

**증상:**
```
java.net.ConnectException: Connection refused: localhost/127.0.0.1:8081
# 또는 q-sign 로그에서:
ERROR KeycloakCallbackService - Keycloak token endpoint 호출 실패
```

**원인**: Keycloak 컨테이너가 기동되지 않았습니다.

**해결:**
```bash
# Keycloak 컨테이너 상태 확인
docker compose -f infra/docker/docker-compose.yml ps | grep keycloak

# Keycloak 기동 (--profile keycloak 필요)
docker compose -f infra/docker/docker-compose.yml --profile keycloak up -d

# 기동까지 대기 (1~2분 소요)
docker compose -f infra/docker/docker-compose.yml logs -f keycloak

# healthy 확인
curl -s http://localhost:8081/health/ready
# {"status":"UP"} 이면 정상
```

---

#### 오류: `Realm 'onepass' not found` — realm-export.json 임포트 실패

**증상:**
```
# Keycloak 로그에서:
ERROR: Failed to import realm: File not found
# 또는 curl http://localhost:8081/realms/onepass 에서 404
```

**원인**: `realm-export.json` 마운트 경로 오류 또는 파일 누락

**해결:**
```bash
# realm-export.json 파일 존재 확인
ls -la infra/docker/keycloak/realm-export.json

# 파일이 없으면 git에서 복구
git checkout -- infra/docker/keycloak/realm-export.json

# Keycloak 컨테이너 재시작 (볼륨은 유지)
docker compose -f infra/docker/docker-compose.yml restart keycloak

# 재시작 후 Realm 임포트 확인
curl -s http://localhost:8081/realms/onepass | python3 -m json.tool | grep '"realm"'
# "realm": "onepass" 이면 정상

# 임포트가 여전히 실패하면 컨테이너 재생성
docker compose -f infra/docker/docker-compose.yml --profile keycloak down
docker compose -f infra/docker/docker-compose.yml --profile keycloak up -d
```

---

#### 오류: `authorizationUrl`이 `kauth.kakao.com`으로 시작함 (잘못된 URL)

**증상:**
```json
{ "authorizationUrl": "https://kauth.kakao.com/oauth/authorize?..." }
```

**원인**: q-sign의 `application.yml`이 올바르게 적용되지 않았거나 이전 버전의 JAR가 실행 중입니다.

**해결:**
```bash
# 1. 클린 빌드 후 재기동
./gradlew :idem-gate:clean :idem-gate:build -x test
./gradlew :idem-gate:bootRun

# 2. q-sign application.yml에 Keycloak 설정 확인
grep -A 10 "keycloak:" idem-gate/src/main/resources/application.yml
# idem.gate.keycloak.base-url 항목이 있어야 함

# 3. 환경변수 확인 (로컬 기동 시)
echo $IDEM_GATE_KEYCLOAK_BASE_URL
# 값이 없으면 application.yml 기본값(http://localhost:8081) 사용
```

---

#### 오류: `state 검증 실패` — CSRF state 불일치

**증상:**
```
PlatformException: IDP_SIGNATURE_MISMATCH — state 검증 실패 (만료 또는 불일치)
# 브라우저 리다이렉트: /error?code=E-IDP-003
```

**원인 및 해결:**
1. **state TTL 만료** (기본 300초): 인증 페이지를 5분 이상 방치 후 로그인 시도
   ```bash
   # state TTL 확인 (application.yml)
   grep "state-ttl-seconds" idem-gate/src/main/resources/application.yml
   # 기본값: 300 (5분). 개발 시 600으로 늘릴 수 있음
   ```
2. **Redis 연결 끊김**: state가 저장되지 않은 경우
   ```bash
   docker exec -it idem-redis redis-cli ping
   # PONG 이면 정상
   docker compose -f infra/docker/docker-compose.yml restart redis
   ```
3. **중복 콜백 요청**: 브라우저 새로고침 등으로 콜백이 두 번 실행된 경우
   - state는 1회 소비(consume) 후 삭제되므로 재사용 불가
   - 재인증 안내 후 처음부터 다시 시도

---

#### 오류: `IDP_SIGNATURE_MISMATCH` — Keycloak JWKS 서명 검증 실패

**증상:**
```
PlatformException: IDP_SIGNATURE_MISMATCH — JWKS 서명 검증 실패
```

**원인**: Keycloak JWKS 엔드포인트에서 공개키를 가져오지 못하거나, 키가 로테이션된 경우

**해결:**
```bash
# 1. Keycloak JWKS 엔드포인트 접근 가능 확인
curl -s http://localhost:8081/realms/onepass/protocol/openid-connect/certs \
  | python3 -m json.tool | grep '"kid"'
# kid 값이 있으면 정상

# 2. keycloakJwks 캐시 만료 강제 (Spring Boot 재시작으로 캐시 초기화)
# q-sign 재시작 시 캐시가 초기화됨
./gradlew :idem-gate:bootRun

# 3. Keycloak 로그에서 오류 확인
docker compose -f infra/docker/docker-compose.yml logs keycloak | tail -50
```

---

#### 오류: `IDEM_GATE_KEYCLOAK_CLIENT_SECRET` 미설정으로 token exchange 실패

**증상:**
```
# Keycloak 응답:
{"error":"unauthorized_client","error_description":"Invalid client credentials"}
```

**원인**: q-sign의 Client Secret이 잘못 설정되었거나 미설정

**해결:**
```bash
# 1. Keycloak Admin Console에서 q-sign-client Secret 확인
# http://localhost:8081 → admin/admin
# Clients → q-sign-client → Credentials 탭 → Secret 복사

# 2. 환경변수로 설정 (Linux/macOS)
export IDEM_GATE_KEYCLOAK_CLIENT_SECRET="복사한-시크릿-값"
./gradlew :idem-gate:bootRun

# Windows PowerShell
$env:IDEM_GATE_KEYCLOAK_CLIENT_SECRET="복사한-시크릿-값"
.\gradlew.bat :idem-gate:bootRun

# 3. application.yml에 직접 설정 (로컬 개발 전용 — 운영 사용 금지)
# idem.gate.keycloak.client-secret: "직접-값-입력"
```

---

#### 오류: `kc_idp_hint`가 URL에 없음 — IdP 힌트 매핑 오류

**증상**: authorizationUrl에 `kc_idp_hint` 파라미터가 없음

**원인**: `idem.gate.keycloak.idp-hint-mapping`에 해당 provider가 없음

**해결:**
```bash
# application.yml 매핑 확인
grep -A 10 "idp-hint-mapping" idem-gate/src/main/resources/application.yml
# 출력 예시:
#   idp-hint-mapping:
#     kakao: social-kakao
#     naver: social-naver
#     pass: social-pass
#     gpki: social-gpki

# 매핑이 없으면 추가 후 재기동
./gradlew :idem-gate:bootRun
```

---

#### 오류: Keycloak Admin Console 로그인 불가 (포트 8081 충돌)

**증상**: `http://localhost:8081` 접속 시 q-sign API 응답이 옴 (Keycloak 화면 아님)

**원인**: q-sign(8081)과 Keycloak(8081) 포트 충돌

**중요**: 이 프로젝트에서 **Keycloak은 8081 포트**를 사용합니다.
q-sign이 `bootRun`으로 실행 중이면 Keycloak 컨테이너와 포트가 충돌합니다.

**해결:**
```bash
# q-sign은 JAR로 별도 포트(8081)에서 실행되지만
# 로컬 개발 시 q-sign bootRun과 Keycloak 컨테이너를 동시에
# 같은 포트에서 실행할 수 없음.
# → Keycloak은 컨테이너, q-sign은 다른 포트로 기동하거나
# → docker-compose.yml에서 Keycloak 포트를 8088로 변경 후 application.yml도 수정

# 임시 해결: Keycloak 포트를 8088로 변경
# infra/docker/docker-compose.yml 내 keycloak 서비스:
#   ports: "8088:8080"  (8081→8088 변경)
# idem-gate/src/main/resources/application.yml:
#   idem.gate.keycloak.base-url: http://localhost:8088
```

> 💡 **권장**: 로컬에서 전체 스택 통합 테스트 시 백엔드 서비스를 `bootRun`이 아닌
> Docker 컨테이너로 실행하면 포트 충돌을 피할 수 있습니다.

---

### 11.11 Windows 전용 오류

---

#### 오류: `'\r': command not found` (Git Bash에서 gradlew 실행 시)

**원인**: Windows CRLF 줄바꿈이 gradlew 스크립트에 적용됨

**해결:**
```bash
# Git Bash에서 실행
sed -i 's/\r//' gradlew
chmod +x gradlew

# 또는 Git 설정으로 체크아웃 시 줄바꿈 변환 방지
git config core.autocrlf false
git checkout -- gradlew
```

---

#### 오류: `The filename or extension is too long` (Windows 경로 길이 제한)

**원인**: Windows 기본 최대 경로 길이(260자)가 node_modules의 중첩된 경로를 수용하지 못함

**해결:**
```powershell
# 관리자 PowerShell에서 Long Path 활성화
New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" `
  -Name "LongPathsEnabled" -Value 1 -PropertyType DWORD -Force

# Git 설정
git config --system core.longpaths true

# 재부팅 후 재시도
```

---

#### 오류: Docker Desktop — `WSL 2 installation is incomplete`

**해결:**
```powershell
# 관리자 PowerShell에서
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart

# 재부팅 후
wsl --update
wsl --set-default-version 2
```

---

#### 오류: `JAVA_HOME is not set` (Windows)

**해결:**
```powershell
# 관리자 PowerShell에서 시스템 환경변수 등록
[System.Environment]::SetEnvironmentVariable(
  "JAVA_HOME",
  "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot",
  "Machine"
)

# 새 PowerShell 창에서 확인
echo $env:JAVA_HOME
```

---

#### 오류: `gradlew.bat` 실행 시 `'.' is not recognized as an internal or external command`

**해결:**
```cmd
REM CMD에서는 경로에 .\ 불필요
gradlew.bat :idem-hub:bootRun

REM PowerShell에서는 .\ 필요
.\gradlew.bat :idem-hub:bootRun
```

---

### 11.12 macOS 전용 오류

---

#### 오류: `Operation not permitted` (Apple Silicon M1/M2/M3)

**원인**: Rosetta 2 없이 x86 이미지 실행 시도 또는 macOS 보안 제한

**해결:**
```bash
# Rosetta 2 설치
softwareupdate --install-rosetta --agree-to-license

# Docker Desktop → Settings → General
# "Use Rosetta for x86/amd64 emulation on Apple Silicon" 체크

# ARM 네이티브 이미지 사용 (일부 이미지는 자동 선택)
docker pull --platform linux/arm64 postgres:16-alpine
```

---

#### 오류: `docker: Error response from daemon: Ports are not available: listen tcp 0.0.0.0:5432: bind: address already in use`

**원인**: macOS에서 기본 PostgreSQL 또는 다른 서비스가 포트를 점유 중

**해결:**
```bash
# 포트 사용 프로세스 확인
lsof -i :5432
lsof -i :6379

# 서비스 중지
brew services stop postgresql@16
brew services stop redis

# 또는 프로세스 강제 종료
kill -9 $(lsof -ti :5432)
```

---

#### 오류: `zsh: permission denied: ./gradlew`

**해결:**
```bash
chmod +x gradlew
./gradlew --version
```

---

#### 오류: Docker Desktop 메모리 부족으로 컨테이너 OOM

**증상**: 컨테이너가 `OOMKilled` 상태로 계속 재시작

**해결:**
```
Docker Desktop → Settings → Resources
- Memory: 8GB 이상으로 증가 (최소 6GB)
- Apply & Restart
```

---

### 11.13 Rate Limiter / PKCE / Admin API 오류

---

#### 오류: `AGENCY_RATE_LIMIT_EXCEEDED` — 기관 Rate Limit 초과

**증상:**
```json
{ "errorCode": "E-AGENCY-306", "message": "기관 요청 한도를 초과했습니다." }
```
HTTP 429 Too Many Requests 응답.

**원인**: AgencyRateLimiter가 TPS(기본 200 req/s) 또는 일별 한도(기본 1,000,000)를 초과했습니다.

**해결:**

```bash
# 1. 현재 TPS 카운터 확인 (Redis)
docker exec -it idem-redis redis-cli \
  KEYS "idem:rl:tps:AGENCY_STUB_001:*"
# 키가 있으면 해당 초에 요청이 집중된 것

# 2. 일별 쿼터 카운터 확인
docker exec -it idem-redis redis-cli \
  GET "idem:rl:daily:AGENCY_STUB_001:$(date +%Y%m%d)"
# 1000000 이상이면 일별 한도 초과

# 3. 로컬 개발 시 Rate Limit 일시 비활성화 방법
# idem-hub/src/main/resources/application.yml에서:
# idem.hub.rate-limit.default-tps: 10000
# idem.hub.rate-limit.daily-limit: 100000000

# 4. DB에서 기관별 Rate Limit 설정 확인/수정 (개발 환경)
docker exec -it idem-postgres psql -U onepass -d onepass -c \
  "SELECT * FROM ido.agency_rate_limit_config;"
# 특정 기관 한도 상향 (개발용)
docker exec -it idem-postgres psql -U onepass -d onepass -c \
  "UPDATE ido.agency_rate_limit_config \
   SET tps_limit=10000, daily_limit=100000000 \
   WHERE agency_code='AGENCY_STUB_001';"

# 5. Redis Rate Limit 카운터 수동 초기화 (긴급 시)
docker exec -it idem-redis redis-cli DEL \
  "idem:rl:daily:AGENCY_STUB_001:$(date +%Y%m%d)"
```

> ⚠️ Rate Limiter는 Redis 오류 시 **Fail-Open** (허용) 방식으로 동작합니다.  
> Redis가 다운되어도 서비스는 계속 동작하지만 Rate Limiting은 비활성화됩니다.

---

#### 오류: PKCE `code_verifier` 검증 실패

**증상:**
```
PkceException: PKCE code_verifier 검증에 실패했습니다.
# 또는 HTTP 400 Bad Request
```

**원인 및 해결:**

1. **PKCE challenge TTL 만료** (기본 300초)
   ```bash
   # application.yml에서 TTL 확인
   grep -A 5 "pkce" idem-gate/src/main/resources/application.yml
   # idem.gate.pkce.challenge-ttl-seconds: 300
   # 개발 시 600으로 늘릴 수 있음
   ```

2. **Redis 연결 문제** — challenge 저장 실패
   ```bash
   docker exec -it idem-redis redis-cli ping
   # PONG 이면 정상
   
   # PKCE 키 존재 여부 확인
   docker exec -it idem-redis redis-cli KEYS "idem:gate:pkce:challenge:*"
   ```

3. **PKCE 비활성화** (로컬 테스트용)
   ```yaml
   # idem-gate/src/main/resources/application.yml
   qsign:
     pkce:
       enabled: false   # 로컬 테스트 전용 — 운영 사용 금지
   ```

4. **code_verifier 형식 오류** — Base64URL 인코딩, 43~128자 범위 필수
   ```bash
   # 올바른 code_verifier 생성 예시 (Python)
   python3 -c "import secrets, base64; \
     v=secrets.token_bytes(64); \
     print(base64.urlsafe_b64encode(v).rstrip(b'=').decode())"
   ```

---

#### 오류: Admin API `403 Forbidden` 또는 빈 응답

**증상**: `/api/v1/admin/agencies/**` 호출 시 403 응답 또는 응답 없음

**원인 및 해결:**

1. **관리자 세션 없음 (401 E-IDO-130) · `X-Requested-With` 없는 쓰기 (403 E-IDO-131)**
   ```bash
   SID=$(scripts/lib/admin-login.sh)      # IDEM_ADMIN_PASSWORD 필요 — docs/admin-auth.md
   curl -H "Cookie: idemAdminSid=$SID" -H 'X-Requested-With: dev' \
     http://localhost:8083/api/v1/admin/agencies
   ```

2. **기관 코드를 찾을 수 없음** → 404 응답
   ```bash
   # DB에서 등록된 기관 목록 확인
   docker exec -it idem-postgres psql -U onepass -d onepass -c \
     "SELECT agency_code, official_name, active FROM ido.agency_meta;"
   
   # 기관이 없으면 V8/V9 마이그레이션 확인
   docker exec -it idem-postgres psql -U onepass -d onepass -c \
     "SELECT version, description, success \
      FROM ido.flyway_schema_history ORDER BY installed_rank;"
   # V8 (seed agency api key), V9 (crypto key registry) 모두 Success이어야 함
   ```

3. **AgencyMeta Redis 캐시 stale** — 캐시 TTL 60분 내 변경 사항 미반영
   ```bash
   # 캐시 수동 삭제 (재기동 없이 즉시 반영)
   docker exec -it idem-redis redis-cli KEYS "idem:agency:*"
   docker exec -it idem-redis redis-cli DEL "idem:agency:AGENCY_STUB_001"
   ```

---

#### 오류: `MemberLookup` — CI 복호화 실패 또는 Q-IM 연결 오류

**증상:**
```
# IdO 로그:
ERROR MemberLookupService - Q-IM 회원 조회 실패: Connection refused
# 또는
ERROR CiCryptoServiceImpl - CI 복호화 실패: AES-256-GCM tag mismatch
```

**원인 및 해결:**

1. **Q-IM 서비스 미기동** — q-im(8082)가 실행되지 않은 경우
   ```bash
   curl http://localhost:8082/actuator/health
   # {"status":"UP"} 이어야 함
   # 기동되지 않았으면:
   ./gradlew :idem-registry:bootRun
   ```

2. **CI 암호화 키 버전 불일치** — IdO와 Q-IM의 키가 다른 경우
   ```bash
   # Q-IM의 active CI 암호화 키 버전 확인 (MariaDB)
   docker exec -it idem-mariadb mariadb -u qim -pqim qim \
     -e "SELECT key_type, key_version, active FROM crypto_key_version;"
   
   # IdO application.yml의 CI 암호화 키 버전 확인
   grep -A 5 "ci-encryption" idem-hub/src/main/resources/application.yml
   # q-im.ci-encryption.current-version 항목 확인
   ```

3. **Q-IM Base URL 설정 오류**
   ```bash
   # ido application.yml에서 Q-IM URL 확인
   grep "qim" idem-hub/src/main/resources/application.yml
   # idem.hub.registry.base-url: http://localhost:8082  (로컬 기동 시)
   ```

4. **member_lookup_log 감사 기록 확인**
   ```bash
   docker exec -it idem-mariadb mariadb -u qim -pqim qim \
     -e "SELECT agency_code, lookup_type, result_code, response_ms, occurred_at \
         FROM member_lookup_log ORDER BY occurred_at DESC LIMIT 20;"
   # result_code: NOT_FOUND / FOUND / ERROR / DECRYPTION_FAILED
   ```

---

#### 오류: HandoffKeyRotationScheduler — AES 키 로테이션 실패

**증상:**
```
ERROR HandoffKeyRotationScheduler - 키 로테이션 실패: 분산 락 획득 불가
# 또는
ERROR HandoffKeyRotationScheduler - 로테이션 중 오류 발생
```

**원인 및 해결:**

1. **분산 락이 해제되지 않음** — 이전 로테이션이 비정상 종료된 경우
   ```bash
   # 로테이션 락 키 존재 여부 확인
   docker exec -it idem-redis redis-cli EXISTS "idem:crypto:aes:rotate-lock"
   # 1 = 락 존재 (정상 로테이션 중), 0 = 락 없음 (정상)
   
   # 락이 오래 지속되면 (비정상 잔류) 수동 삭제
   docker exec -it idem-redis redis-cli DEL "idem:crypto:aes:rotate-lock"
   ```

2. **현재 키 버전 확인 및 수동 초기화**
   ```bash
   # 현재 버전 확인
   docker exec -it idem-redis redis-cli GET "idem:crypto:aes:current-version"
   
   # 등록된 키 버전 목록
   docker exec -it idem-redis redis-cli KEYS "idem:crypto:aes:version:*"
   
   # DB crypto_key_registry 확인
   docker exec -it idem-postgres psql -U onepass -d onepass -c \
     "SELECT key_type, key_version, active, current_flag, grace_until \
      FROM ido.crypto_key_registry ORDER BY created_at;"
   ```

3. **로컬 환경에서 스케줄러 비활성화 (선택)**
   ```yaml
   # idem-hub/src/main/resources/application.yml
   ido:
     ticket:
       key-rotation-days: 36500  # 사실상 비활성화 (100년)
   ```

---

## 12. 개발 Tips 및 유용한 명령어

### 12.1 Gradle 빠른 명령어 모음

```bash
# 특정 모듈만 빌드 (빠름)
./gradlew :idem-hub:build -x test

# 변경된 모듈만 빌드 (Gradle incremental build)
./gradlew :idem-hub:bootRun   # 변경 감지 자동

# 전체 클린 빌드
./gradlew clean build -x test

# 의존성 트리 확인
./gradlew :idem-hub:dependencies

# 사용 가능한 태스크 목록
./gradlew :idem-hub:tasks

# Gradle 데몬 중지 (메모리 해제)
./gradlew --stop
```

### 12.2 Docker 유용한 명령어

```bash
# 실행 중인 컨테이너 전체 상태 (헬스체크 포함)
docker compose -f infra/docker/docker-compose.yml ps

# 특정 서비스 재시작
docker compose -f infra/docker/docker-compose.yml restart kafka

# 컨테이너 내부 접속
docker exec -it idem-postgres bash
docker exec -it idem-kafka bash
docker exec -it idem-redis redis-cli

# 로그 실시간 확인 (최근 100줄부터)
docker compose -f infra/docker/docker-compose.yml logs -f --tail=100 kafka

# 컨테이너 리소스 사용량 확인
docker stats

# 네트워크 확인
docker network ls
docker network inspect onepass_idem-net
```

### 12.3 MariaDB 유용한 명령어 (Q-IM 전용)

```bash
# MariaDB CLI 접속
docker exec -it idem-mariadb mariadb -u qim -pqim qim

# 테이블 목록
SHOW TABLES;

# 기본 테이블 조회
SELECT * FROM qim_user LIMIT 10;
SELECT * FROM auth_mean_mapping WHERE status = 'ACTIVE' LIMIT 10;
SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY created_at LIMIT 10;

# Flyway 마이그레이션 이력 확인
SELECT version, description, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank;
# 정상: V1, V2, V3 모두 success=1

# 사용자 상태 분포
SELECT status, COUNT(*) FROM qim_user GROUP BY status;

# ★ V3 신규 — 사용자 상태 전이 이력 조회
SELECT qim_user_id, old_status, new_status, reason, changed_by, changed_at
FROM user_status_history ORDER BY changed_at DESC LIMIT 20;

# ★ V3 신규 — CI 암호화 키 버전 현황
SELECT key_type, key_version, active, grace_until, created_at
FROM crypto_key_version ORDER BY created_at;
# active=1 인 행이 현재 사용 중인 버전
```

### 12.4 PostgreSQL 유용한 명령어

```bash
# psql 접속
docker exec -it idem-postgres psql -U onepass -d onepass

# 스키마별 테이블 목록
\dn          -- 스키마 목록 (qim 없음 — MariaDB 이관)
\dt qsign.*  -- qsign 스키마 테이블 목록
\dt ido.*    -- ido 스키마 테이블 목록

# 기본 테이블 조회
SELECT * FROM ido.agency_meta LIMIT 10;

# Flyway 마이그레이션 이력 확인
SELECT version, description, success FROM ido.flyway_schema_history ORDER BY installed_rank;
# 정상: V1 ~ V9 모두 success=true

# ★ V9 신규 — crypto_key_registry 확인
SELECT key_type, key_version, active, current_flag, grace_until
FROM ido.crypto_key_registry ORDER BY created_at;

# ★ V9 신규 — 기관별 Rate Limit 설정 확인
SELECT agency_code, tps_limit, daily_limit, burst_multiplier, enabled
FROM ido.agency_rate_limit_config;

# ★ V9 신규 — 기관 설정 변경 이력 확인
SELECT agency_code, policy_version, changed_by, change_reason, changed_at
FROM ido.agency_meta_history ORDER BY changed_at DESC LIMIT 20;

# ★ V9 신규 — 회원 조회 감사 로그 확인
SELECT agency_code, lookup_type, result_code, response_ms, occurred_at
FROM ido.member_lookup_log ORDER BY occurred_at DESC LIMIT 20;
```

### 12.5 Redis 유용한 명령어

```bash
# Redis CLI 접속
docker exec -it idem-redis redis-cli

# --- 기존 키 패턴 ---

# FE 세션 키 목록
KEYS fe:session:*

# 특정 세션 조회
GET fe:session:[feSessionId]

# 사용자별 세션 목록
SMEMBERS fe:user-sessions:[qimUserId]

# OIDC 상태 키 확인
KEYS oidc:state:*

# 전체 키 개수
DBSIZE

# 메모리 사용량
INFO memory
```

### 12.6 Redis 키 패턴 전체 목록 (v1.8.0 기준)

| 키 패턴 | TTL | 설명 | 담당 컴포넌트 |
|---------|-----|------|---------------|
| `idem:fe:session:{feSessionId}` | 30분 | FE 세션 데이터 | IdO |
| `idem:fe:user-sessions:{qimUserId}` | 30분 | 사용자 세션 목록 (Set) | IdO |
| `oidc:state:{state}` | 5분 | OIDC CSRF state | Q-Sign |
| `idem:gate:pkce:challenge:{state}` | 5분 (설정 가능) | PKCE code_challenge | Q-Sign (PkceService) |
| `idem:idempotency:handoff:{key}` | 60초 | Handoff 멱등 처리 키 | IdO |
| `idem:rl:tps:{agencyCode}:{epochSecond}` | 2초 | Rate Limit TPS 슬라이딩 카운터 | IdO (AgencyRateLimiter) |
| `idem:rl:daily:{agencyCode}:{yyyyMMdd}` | 25시간 | Rate Limit 일별 누적 카운터 | IdO (AgencyRateLimiter) |
| `idem:crypto:aes:current-version` | 없음 | 현재 활성 AES 키 버전 (e.g., `v1`) | IdO (HandoffKeyRotationScheduler) |
| `idem:crypto:aes:version:{vN}` | 없음 | AES 키 버전별 Base64 인코딩 키 | IdO (HandoffKeyRotationScheduler) |
| `idem:crypto:aes:rotate-lock` | 5분 | 키 로테이션 분산 락 | IdO (HandoffKeyRotationScheduler) |

```bash
# 전체 IdO Rate Limit 키 확인
docker exec -it idem-redis redis-cli KEYS "idem:rl:*"

# 전체 PKCE 키 확인
docker exec -it idem-redis redis-cli KEYS "idem:gate:pkce:*"

# 전체 AES 암호화 관련 키 확인
docker exec -it idem-redis redis-cli KEYS "idem:crypto:*"

# 특정 기관의 오늘 Rate Limit 카운터 조회
AGENCY=AGENCY_STUB_001
DATE=$(date +%Y%m%d)
docker exec -it idem-redis redis-cli GET "idem:rl:daily:${AGENCY}:${DATE}"

# 현재 AES 키 버전 조회
docker exec -it idem-redis redis-cli GET "idem:crypto:aes:current-version"
```

### 12.7 Kafka 유용한 명령어

```bash
# 토픽 목록 확인
docker exec -it idem-kafka \
  kafka-topics --bootstrap-server localhost:9092 --list

# 토픽 상세 정보
docker exec -it idem-kafka \
  kafka-topics --bootstrap-server localhost:9092 \
  --describe --topic idem.gate.auth.events

# 토픽 메시지 실시간 소비 (처음부터)
docker exec -it idem-kafka \
  kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic idem.gate.auth.events \
  --from-beginning

# 컨슈머 그룹 목록
docker exec -it idem-kafka \
  kafka-consumer-groups --bootstrap-server localhost:9092 --list

# 컨슈머 그룹 lag 확인
docker exec -it idem-kafka \
  kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group ido-qsign-consumer
```

### 12.8 Spring Boot Actuator 엔드포인트

```bash
# 헬스체크 (상세)
curl http://localhost:8083/actuator/health | python3 -m json.tool

# 환경변수 확인
curl http://localhost:8083/actuator/env | python3 -m json.tool

# 빈 목록
curl http://localhost:8083/actuator/beans | python3 -m json.tool

# 메트릭
curl http://localhost:8083/actuator/metrics
curl http://localhost:8083/actuator/metrics/jvm.memory.used
```

---

## 13. 서비스 종료 방법

### 13.1 Spring Boot 서비스 종료

각 서비스가 실행 중인 터미널에서:

```
Ctrl + C
```

Spring Boot는 Graceful Shutdown이 설정되어 있으므로 진행 중인 요청 처리 후 종료됩니다 (최대 20초).

### 13.2 프론트엔드 개발 서버 종료

React 개발 서버가 실행 중인 터미널에서:

```
Ctrl + C
```

### 13.3 Docker 인프라 종료

```bash
# 컨테이너 중지 (데이터 유지)
docker compose -f infra/docker/docker-compose.yml stop

# 컨테이너 중지 및 제거 (데이터 볼륨 유지)
docker compose -f infra/docker/docker-compose.yml down

# 컨테이너 + 볼륨 전체 제거 (데이터 완전 삭제 — 주의!)
docker compose -f infra/docker/docker-compose.yml down -v
```

> ⚠️ `down -v` 옵션을 사용하면 **PostgreSQL, Kafka, Redis의 모든 데이터가 삭제**됩니다.  
> 다음 기동 시 Flyway 마이그레이션이 처음부터 다시 실행됩니다.

### 13.4 전체 환경 재시작 (클린 재시작)

```bash
# 1. 모든 Spring Boot 서비스 종료 (각 터미널에서 Ctrl+C)

# 2. Docker 인프라 재시작 (데이터 유지)
docker compose -f infra/docker/docker-compose.yml down
docker compose -f infra/docker/docker-compose.yml up -d

# 3. healthy 상태 확인 후 Spring Boot 재기동
docker compose -f infra/docker/docker-compose.yml ps

# 4. Spring Boot 서비스 순서대로 재기동
./gradlew :idem-gate:bootRun   # 터미널 1
./gradlew :idem-registry:bootRun     # 터미널 2
./gradlew :idem-hub:bootRun      # 터미널 3
./gradlew :idem-tenant-sample:bootRun  # 터미널 4

# 5. 프론트엔드 재기동
cd idem-console/frontend && yarn dev  # 터미널 5
```

---

## 부록 A. 로컬 환경 기동 순서 요약 체크리스트

```
사전 준비
□ JDK 21 설치 확인 (java -version → openjdk 21.x.x)
□ Docker 및 Docker Compose v2 설치 확인 (docker compose version)
□ Node.js 20 LTS 설치 확인 (node --version → v20.x.x)
□ Yarn 1.22 설치 확인 (yarn --version → 1.22.x)
□ 프로젝트 클론 및 브랜치 확인 (git branch)
□ chmod +x gradlew (Linux/macOS)

Step 1 — 인프라 기동
□ docker compose -f infra/docker/docker-compose.yml up -d
□ 30~60초 대기

Step 2 — 인프라 확인
□ docker compose ps → 모두 "Up (healthy)" 확인
□ kafka-init → "Exited (0)" 확인 (1이면 오류 → §11.4)
□ Kafka 토픽 11개 생성 확인
□ MariaDB Flyway: V1~V3 모두 Success 확인 (Q-IM 전용)
□ PostgreSQL Flyway: V1~V9 모두 Success 확인 (IdO 기준)
□ Redis AES 키 초기화 확인: GET ido:crypto:aes:current-version → "v1"

Step 3 — 백엔드 빌드
□ ./gradlew :idem-common:build :idem-gate:build :idem-registry:build :idem-hub:build :idem-tenant-sample:build -x test
□ BUILD SUCCESSFUL 확인 (빌드 오류 시 → §11.6)

Step 4 — 백엔드 기동 (4개 터미널)
□ 터미널 1: ./gradlew :idem-gate:bootRun → http://localhost:8081/actuator/health {"status":"UP"}
□ 터미널 2: ./gradlew :idem-registry:bootRun   → http://localhost:8082/actuator/health {"status":"UP"}
□ 터미널 3: ./gradlew :idem-hub:bootRun    → http://localhost:8083/actuator/health {"status":"UP"}
□ 터미널 4: ./gradlew :idem-tenant-sample:bootRun → http://localhost:8084/actuator/health {"status":"UP"}

Step 5 — 프론트엔드 기동 (1개 터미널)
□ 터미널 5: cd idem-console/frontend && yarn install && yarn dev
□ http://localhost:3000 접속 확인

기본 모니터링 확인 (기본 기동 시)
□ Kafka UI: http://localhost:8090 (admin/admin) — 토픽 목록 확인
□ Redis Insight: http://localhost:5540 — Redis 키 확인

[선택] v1.8.0 신규 기능 확인
□ Monitoring 스택 기동: docker compose --profile monitoring up -d
□ Prometheus 정상: http://localhost:9090/-/healthy
□ Grafana 정상: http://localhost:3002 (admin/admin)
□ Admin API 동작 확인: SID=$(scripts/lib/admin-login.sh); curl -H "Cookie: idemAdminSid=$SID" http://localhost:8083/api/v1/admin/agencies
□ Rate Limiter Redis 키 확인: KEYS idem:rl:*
□ PKCE 활성화 확인: grep pkce idem-gate/src/main/resources/application.yml
□ AES 키 버전 확인: GET ido:crypto:aes:current-version
```

---

## 부록 B. 환경별 기본 설정값 요약

### B.1 인프라 연결 설정

| 설정 | 로컬 기본값 | Docker 기본값 |
|------|-----------|-------------|
| PostgreSQL Host (IdO/Q-Sign) | `localhost` | `postgres` |
| PostgreSQL Port | `5432` | `5432` |
| PostgreSQL DB | `onepass` | `onepass` |
| PostgreSQL User | `onepass` | `onepass` |
| PostgreSQL Password | `onepass` | `onepass` |
| **MariaDB Host (Q-IM)** | **`localhost`** | **`mariadb`** |
| **MariaDB Port** | **`3306`** | **`3306`** |
| **MariaDB DB** | **`qim`** | **`qim`** |
| **MariaDB User (Q-IM)** | **`qim`** | **`qim`** |
| **MariaDB Password (Q-IM)** | **`qim`** | **`qim`** |
| Redis Host | `localhost` | `redis` |
| Redis Port | `6379` | `6379` |
| Kafka Servers | `localhost:9092` | `kafka:29092` |
| Q-IM Base URL | `http://localhost:8082` | `http://idem-registry:8082` |
| Q-Sign Base URL | `http://localhost:8081` | `http://idem-gate:8081` |
| IDO Broker Mode | `qsign` | `qsign` |
| Keycloak Base URL | `http://localhost:8088` | `http://keycloak:8080` |

### B.2 v1.8.0 신규 환경변수 (application.yml 설정)

> ⚠️ 로컬 개발 환경에서는 아래 값이 `application.yml` placeholder로 설정되어 있습니다.  
> **운영 환경에서는 반드시 실제 비밀 값으로 교체하고 Vault/AWS KMS를 사용하세요.**

| 설정 키 (application.yml) | 기본값 (로컬/PoC) | 설명 |
|--------------------------|-----------------|------|
| `q-im.ci-encryption.keys[0].version` | `v1` | CI 암호화 AES 키 버전 |
| `q-im.ci-encryption.keys[0].key` | `(placeholder)` | AES-256 키 (Base64) |
| `q-im.ci-encryption.current-version` | `v1` | 현재 활성 버전 |
| `q-im.di.secret` | `(placeholder)` | DI 생성 HMAC-SHA256 비밀 (`IDEM_REGISTRY_DI_SECRET`) |
| `idem.hub.rate-limit.default-tps` | `200` | 기관 기본 TPS 한도 |
| `idem.hub.rate-limit.daily-limit` | `1000000` | 기관 기본 일별 한도 |
| `idem.hub.ticket.key-rotation-days` | `90` | AES 키 로테이션 주기(일) |
| `idem.hub.ticket.key-grace-period-hours` | `24` | 로테이션 후 구 버전 유예 기간 |
| `idem.gate.pkce.enabled` | `true` | PKCE RFC 7636 활성화 여부 |
| `idem.gate.pkce.challenge-ttl-seconds` | `300` | PKCE challenge Redis TTL (초) |

```bash
# 로컬에서 환경변수로 override 예시
export IDEM_REGISTRY_DI_SECRET="local-dev-secret-not-for-production"
export IDEM_REGISTRY_CI_KEY_V1="bG9jYWwtZGV2LWtleS1ub3QtZm9yLXByb2Q="  # Base64
./gradlew :idem-registry:bootRun
```
