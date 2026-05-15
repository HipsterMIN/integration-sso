# 운영 준비도 분석 v3.0
**작성일**: 2026-05-15  
**작성자**: Genspark AI Developer  
**이전 버전**: v2.2 (2026-05-08), 교차검증 보고서 (2026-05-12)

---

## 1. 이번 세션 작업 요약 (2026-05-15)

### 1.1 완료된 수정 항목

| Task | 파일 | 수정 내용 | 분류 |
|------|------|-----------|------|
| T-4a | `ConversionSteps/member/components/AccountForm.tsx` | 기업 진위확인 API 주석 해제 (`businessValidate` 호출 복원, `startDt` 필수값 체크 복원) | HIGH |
| T-4b | `RegisterSteps/member/components/AccountForm.tsx` | 동일 패턴 — `handleBrnoDuplicate` 내 진위확인 스킵 코드 제거, `businessValidate` 호출 복원, `startDt` 필수값 체크 추가 | HIGH |
| T-5 | `.github/workflows/ci.yml` | OWASP Dependency-Check `\|\| true` 제거 → CVSS 7.0+ 발견 시 빌드 실패 (보안 게이트 활성화) | BLOCKER |
| T-6 | `.github/workflows/ci.yml` | `-x :q-im:test` 제거 → Q-IM 모듈 테스트 CI 포함 | HIGH |
| T-7 | `q-sign/src/main/java/kr/go/smes/qsign/api/InternalSigVerifier.java` | non-strict 코드 경로 완전 삭제; `strictMode` 필드 제거; `verify()` 메서드 strict-only 로직으로 재작성 | BLOCKER |
| T-8b | `q-im/src/main/java/kr/go/smes/qim/config/KafkaConsumerConfig.java` | `DeadLetterPublishingRecoverer` 연결; 지수 백오프 3회(1s→2s→4s) 후 `{topic}.dlt` 전송; DLQ 헤더 6종 보존 | HIGH |

### 1.2 이전 세션(2026-05-13~14)에서 완료된 항목

| Task | 파일 | 수정 내용 | 분류 |
|------|------|-----------|------|
| T-1 | `infra/docker/docker-compose.yml` | `IDO_HANDOFF_AES_KEY`, `IDO_HANDOFF_HMAC_KEY`, `QIM_CI_AES_KEY_V1/V2`, `IDO_WEBHOOK_SIGNING_SECRET`, `IDO_AGENCY_SUBJECT_SECRET` 환경변수 블록 추가; `MARIADB_PASSWORD`, `POSTGRES_PASSWORD` 환경변수화; `.env` 생성 안내 추가 | BLOCKER×4 |
| T-2 | `ido/src/main/java/kr/go/smes/ido/provision/ProvisioningServiceImpl.java` | `PLACEHOLDER_` 인증 헤더 → `ERROR` 로그 + `REQUIRES_MANUAL_` 헤더로 교체 | BLOCKER |
| T-3a | `onepass-fe/frontend/src/pages/Mypage/pages/PasswordStep1.tsx` | `handleEasyAuthSuccess`, `handlePhoneAuthSuccess` — `history.push(onNext)` 복원; `useCallback` 의존성 배열 수정 | HIGH |
| T-3b | `onepass-fe/frontend/src/pages/Mypage/pages/Withdraw.tsx` | `goNext` 함수 추가; "다음" 버튼 `onClick` 복원; devNoticeModal 관련 코드 제거 | HIGH |

---

## 2. 현재 운영 준비도 매트릭스 (v3.0)

### 2.1 BLOCKER 항목 (운영 이관 차단)

| ID | 항목 | 상태 | 비고 |
|----|------|------|------|
| B-1 | Handoff AES/HMAC 키 미주입 | ✅ **코드 해소** | docker-compose 환경변수 블록 추가; ⚠️ 실 값 주입은 REQUIRES_MANUAL |
| B-2 | CI AES 키 미주입 (QIM_CI_AES_KEY_V1) | ✅ **코드 해소** | docker-compose 추가; ⚠️ 실 값 주입 REQUIRES_MANUAL |
| B-3 | Webhook 시크릿 미주입 | ✅ **코드 해소** | docker-compose 추가; ⚠️ 실 값 주입 REQUIRES_MANUAL |
| B-4 | Feature Flags 기본값 false | ⚠️ **REQUIRES_MANUAL** | F-20(프로비저닝), F-23(GatewayInbound), F-26(HMAC) — 운영 시 명시적 활성화 필요 |
| B-5 | DB 비밀번호 하드코딩 | ✅ **코드 해소** | 환경변수화; ⚠️ 실 값 주입 REQUIRES_MANUAL |
| B-6 | OWASP 보안 게이트 무력화 | ✅ **완료** | `\|\| true` 제거; CVE CVSS 7.0+ 시 빌드 실패 |
| B-7 | Provisioning 인증 PLACEHOLDER | ✅ **코드 해소** | ERROR 로그로 교체; ⚠️ Sprint 17 K8s Secret 조회 구현 필요 |
| B-8 | InternalSigVerifier non-strict 경로 | ✅ **완료** | strict-only 로직; `strictMode` 필드 제거 |
| B-9 | 기업 진위확인 API 스킵 | ✅ **완료** | `businessValidate` 호출 복원 (ConversionSteps + RegisterSteps) |

**BLOCKER 해소율**: 8/9 코드 처리 완료 (B-4만 REQUIRES_MANUAL 잔존)

### 2.2 HIGH 항목

| ID | 항목 | 상태 | 비고 |
|----|------|------|------|
| H-1 | Q-IM 테스트 CI 제외 | ✅ **완료** | `-x :q-im:test` 제거 |
| H-2 | Kafka DLQ 미연결 | ✅ **완료** | IdO, Q-Sign: 이미 구현됨 확인; Q-IM: 이번 세션 구현 |
| H-3 | Mypage 비밀번호 변경 플로우 단절 | ✅ **완료** | `history.push(onNext)` 복원 |
| H-4 | 탈퇴 플로우 단절 | ✅ **완료** | `goNext` 함수 복원 |
| H-5 | AgencyRateLimiter 단일 JVM | ✅ **확인 완료** | Redis Lua 스크립트 기반으로 이미 구현됨 — 추가 작업 불필요 |
| H-6~H-10 | (기타) | ⚠️ **미검토** | Sprint 14~18 계획 내 처리 예정 |

### 2.3 REQUIRES_MANUAL 잔존 항목 (코드로 불가)

> 아래 항목은 **반드시 운영 배포 전 수동으로 처리**해야 합니다.

| 우선순위 | 항목 | 조치 방법 |
|---------|------|-----------|
| 🔴 필수 | `IDO_HANDOFF_AES_KEY` / `IDO_HANDOFF_HMAC_KEY` 실 값 주입 | `openssl rand -base64 32` → `.env` 또는 K8s Secret |
| 🔴 필수 | `QIM_CI_AES_KEY_V1` 실 값 주입 | `openssl rand -base64 32` → `.env` 또는 K8s Secret |
| 🔴 필수 | `IDO_WEBHOOK_SIGNING_SECRET` 실 값 주입 | `openssl rand -hex 32` → `.env` 또는 K8s Secret |
| 🔴 필수 | `IDO_INTERNAL_SIG_SECRET` 실 값 주입 | `openssl rand -hex 32` → Q-Sign 환경변수 |
| 🔴 필수 | `QSIGN_KEYCLOAK_CLIENT_SECRET` | Keycloak Admin Console에서 발급 |
| 🔴 필수 | `KEYCLOAK_IDO_CLIENT_SECRET` | Keycloak Admin Console에서 발급 |
| 🔴 필수 | `POSTGRES_PASSWORD` / `MARIADB_PASSWORD` 실 값 주입 | `openssl rand -base64 32` → `.env` 또는 K8s Secret |
| 🟡 권장 | Feature Flag F-20, F-23, F-26 활성화 | `application.yml` 또는 환경변수 설정 |
| 🟡 권장 | 68개 기관 실 API 엔드포인트 DB 등록 | `agency_endpoint` 테이블 데이터 투입 |
| 🟡 권장 | Sprint 17: K8s Secret 기반 기관 API Key 조회 구현 | ProvisioningServiceImpl 완성 |
| 🟡 권장 | 법무팀 개인정보 보존 기간 확정 → `IDO_RETENTION_DAYS` | 법무 협의 후 환경변수 설정 |
| 🟡 권장 | mTLS 클라이언트 인증서 설정 | Sprint 17 완성 후 처리 |

---

## 3. 파일 수정 대장 (전체 세션)

| 파일 경로 | 수정 유형 | 핵심 변경 |
|-----------|-----------|-----------|
| `infra/docker/docker-compose.yml` | MODIFIED | 암호화 키 환경변수 블록 추가; DB 비밀번호 환경변수화 |
| `ido/.../ProvisioningServiceImpl.java` | MODIFIED | PLACEHOLDER_ → REQUIRES_MANUAL_ + ERROR 로그 |
| `onepass-fe/.../Mypage/PasswordStep1.tsx` | MODIFIED | history.push(onNext) 복원; useCallback 의존성 수정 |
| `onepass-fe/.../Mypage/Withdraw.tsx` | MODIFIED | goNext 복원; devNoticeModal 제거 |
| `onepass-fe/.../ConversionSteps/.../AccountForm.tsx` | MODIFIED | businessValidate 호출 복원; startDt 필수값 체크 복원 |
| `onepass-fe/.../RegisterSteps/.../AccountForm.tsx` | MODIFIED | businessValidate 호출 복원; startDt 필수값 체크 복원 |
| `.github/workflows/ci.yml` | MODIFIED | OWASP `\|\| true` 제거; `-x :q-im:test` 제거 |
| `q-sign/.../InternalSigVerifier.java` | MODIFIED | non-strict 코드 경로 삭제; strictMode 필드 제거 |
| `q-im/.../KafkaConsumerConfig.java` | MODIFIED | DLQ DeadLetterPublishingRecoverer 연결 |

---

## 4. 다음 단계 권고

### Sprint 17 핵심 구현 목표
1. K8s Secret 기반 기관별 API Key 조회 (`ProvisioningServiceImpl`)
2. HMAC-SHA256 서명 생성 (`ProvisioningServiceImpl`)
3. mTLS 클라이언트 인증서 설정
4. Feature Flags F-20, F-23, F-26 운영 활성화

### 운영 배포 전 .env 생성 스크립트 예시
```bash
# infra/docker/.env 생성
cat > infra/docker/.env << 'EOF'
POSTGRES_PASSWORD=$(openssl rand -base64 32)
MARIADB_ROOT_PASSWORD=$(openssl rand -base64 32)
MARIADB_PASSWORD=$(openssl rand -base64 32)
IDO_HANDOFF_AES_KEY=$(openssl rand -base64 32)
IDO_HANDOFF_HMAC_KEY=$(openssl rand -base64 32)
QIM_CI_AES_KEY_V1=$(openssl rand -base64 32)
IDO_WEBHOOK_SIGNING_SECRET=$(openssl rand -hex 32)
IDO_INTERNAL_SIG_SECRET=$(openssl rand -hex 32)
IDO_AGENCY_SUBJECT_SECRET=$(openssl rand -hex 32)
# 아래는 Keycloak Admin Console에서 직접 발급
QSIGN_KEYCLOAK_CLIENT_SECRET=<발급값>
KEYCLOAK_IDO_CLIENT_SECRET=<발급값>
EOF
```

---

*이 문서는 Genspark AI Developer에 의해 자동 생성되었습니다.*  
*PR: https://github.com/HipsterMIN/integration-sso/pull/110 (업데이트 예정)*
