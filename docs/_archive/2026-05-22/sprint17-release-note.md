# Sprint 17 릴리스 노트 — v2.3.0

> **릴리스 일자**: 2026-05-14  
> **버전**: `2.3.0` (이전: `2.2.0`)  
> **브랜치**: `shipster` → PR #93

---

## 릴리스 요약

Sprint 17은 **보안 강화(HMAC 서명 인프라)**, **운영 자동화(Helm Chart)**, **성능 검증(k6)** 세 축으로 구성됩니다.  
F-26 HMAC 서명은 **인프라만 구현**했고, 기본값은 `false`를 유지합니다.  
모든 기관이 준비된 후 `--set phase=4` 한 줄로 Phase 4에 진입합니다.

---

## 신규 기능

### 1. F-26 HMAC-SHA256 서명 인프라 완성

#### HmacSignatureFilter.java (신규)
- `POST /api/v1/agency/gateway/inbound/event` 전용 `OncePerRequestFilter`
- **소프트 모드** (`IDO_HMAC_SIG_REQUIRED=false`): 헤더 있으면 검증, 없으면 경고 로그만
- **필수 모드** (`IDO_HMAC_SIG_REQUIRED=true`): 헤더 없거나 불일치 시 `401 Unauthorized`
- 서명 페이로드: `"{agencyCode}:{idempotencyKey}:{epochSeconds}"`
- ±60초 범위 전수 검사 (네트워크 지연 + 시계 편차 허용)
- `MessageDigest.isEqual()` 상수시간 비교 — 타이밍 공격 방어

#### AgencyHmacKeyStore.java (신규)
- 기관별 독립 HMAC 비밀키 관리 (K8s Secret `ido-gateway-hmac-keys`)
- 환경변수 명명 규칙: `IDO_GATEWAY_HMAC_KEY_{기관코드}`
- 런타임 키 갱신 API (`refresh()`) — Pod 재시작 없는 무중단 키 로테이션 지원
- 기동 시 등록 기관 목록 로그 출력 (키 값 마스킹 처리)
- 개발 환경용 `ido.gateway.hmac.dev-keys` 설정 지원

#### AgencyGatewayServiceImpl 개선
- 아웃바운드 발송 시 `HMAC_PLACEHOLDER_` → 실제 HMAC-SHA256 서명으로 교체
- `buildOutboundHmacSig()`: 동일한 페이로드 규칙으로 서명 생성
- 기관 키 미등록 시 X-Internal-Sig 헤더 생략 + 경고 로그

---

### 2. Helm Chart 패키징 (infra/helm/idem-hub/)

```
infra/helm/idem-hub/
├── Chart.yaml                  # appVersion: 2.3.0
├── values.yaml                 # 기본값 (Phase 1)
├── values-prod.yaml            # 운영 오버라이드
└── templates/
    ├── _helpers.tpl            # Phase 프리셋 헬퍼
    ├── configmap.yaml          # Feature Flags ConfigMap
    ├── deployment.yaml         # Deployment (HMAC Secret 마운트 포함)
    ├── service.yaml            # ClusterIP Service
    ├── serviceaccount.yaml     # ServiceAccount
    ├── hpa.yaml                # HPA (autoscaling.enabled=true 시)
    └── pdb.yaml                # PodDisruptionBudget
```

#### Phase 프리셋 기능

`--set phase=<value>` 한 줄로 Feature Flag 일괄 전환:

```bash
helm upgrade ido ./infra/helm/idem-hub -n production -f values-prod.yaml --set phase=2a
# → IDO_PROVISIONING_ENABLED=true, IDO_PROVISIONING_DRY_RUN=true 자동 적용
```

| `--set phase=` | 활성화 플래그 |
|----------------|-------------|
| `1` (기본) | 신규 기능 전부 OFF |
| `2a` | `PROVISIONING_ENABLED=true` + `DRY_RUN=true` |
| `2b` | `PROVISIONING_ENABLED=true` + `DRY_RUN=false` + `RELAY=true` |
| `3a` | Phase 2b + `GATEWAY_INBOUND=true` |
| `3b` | Phase 3a + `GATEWAY_OUTBOUND=true` |
| `4` | Phase 3b + `HMAC_SIG_REQUIRED=true` |

---

### 3. k6 부하 테스트 스크립트 (test/load/)

#### k6-provisioning.js
- 68개 기관 동시 Virtual Thread HTTP 발행 시뮬레이션
- 5단계 부하 프로파일 (워밍업 → 피크 100 VU → 쿨다운)
- SLO: p95 < 2,000ms, 에러율 < 0.1%

```bash
# 실행
k6 run test/load/k6-provisioning.js \
  -e BASE_URL=https://ido.staging.smes.go.kr
```

#### k6-hmac-verification.js
- HMAC 서명 검증 성능 테스트 (3개 시나리오 병렬)
- 유효 서명 통과율, 무효 서명 거부율, HMAC 오버헤드 p99 측정
- SLO: 오버헤드 p99 < 5ms, 유효 서명 통과율 > 99%

```bash
# F-26=true 환경에서 실행
k6 run test/load/k6-hmac-verification.js \
  -e BASE_URL=https://ido.staging.smes.go.kr \
  -e HMAC_SECRET=your-test-secret
```

---

### 4. F-26 문서 전면 보완 (docs/features/F-26-hmac-sig.md)

- 서명 페이로드 규칙 명확화 (`{agencyCode}:{idempotencyKey}:{epochSeconds}`)
- Java/Python 서명 생성 예시 추가
- K8s Secret 등록 및 키 로테이션 절차
- 에러 코드 표 (5종)
- Phase 4 전환 체크리스트 업데이트
- 모니터링 SQL 쿼리 추가

---

## 변경된 파일 목록

| 파일 | 변경 유형 | 설명 |
|------|-----------|------|
| `idem-hub/.../gateway/HmacSignatureFilter.java` | **신규** | F-26 인바운드 서명 검증 필터 |
| `idem-hub/.../gateway/AgencyHmacKeyStore.java` | **신규** | 기관별 HMAC 키 저장소 |
| `idem-hub/.../gateway/AgencyGatewayServiceImpl.java` | **수정** | 아웃바운드 실제 HMAC 서명 적용 |
| `infra/helm/idem-hub/Chart.yaml` | **신규** | Helm Chart 메타데이터 |
| `infra/helm/idem-hub/values.yaml` | **신규** | 기본 values (Phase 1) |
| `infra/helm/idem-hub/values-prod.yaml` | **신규** | 운영 오버라이드 |
| `infra/helm/idem-hub/templates/_helpers.tpl` | **신규** | Phase 프리셋 헬퍼 |
| `infra/helm/idem-hub/templates/configmap.yaml` | **신규** | Feature Flags ConfigMap 템플릿 |
| `infra/helm/idem-hub/templates/deployment.yaml` | **신규** | Deployment 템플릿 (HMAC Secret 포함) |
| `infra/helm/idem-hub/templates/service.yaml` | **신규** | Service 템플릿 |
| `infra/helm/idem-hub/templates/serviceaccount.yaml` | **신규** | ServiceAccount 템플릿 |
| `infra/helm/idem-hub/templates/hpa.yaml` | **신규** | HPA 템플릿 |
| `infra/helm/idem-hub/templates/pdb.yaml` | **신규** | PDB 템플릿 |
| `test/load/k6-provisioning.js` | **신규** | 프로비저닝 부하 테스트 |
| `test/load/k6-hmac-verification.js` | **신규** | HMAC 검증 부하 테스트 |
| `docs/features/F-26-hmac-sig.md` | **보완** | Sprint 17 구현 내용 전면 반영 |
| `docs/sprint17-release-note.md` | **신규** | 이 문서 |

---

## 빌드 검증

```
./gradlew :idem-hub:compileJava  → ✅ BUILD SUCCESSFUL
./gradlew :idem-hub:test         → ✅ BUILD SUCCESSFUL
```

---

## 배포 절차

```bash
# 1. 기관별 HMAC 키 Secret 등록 (F-26 사용 기관부터)
kubectl create secret generic ido-gateway-hmac-keys \
  --from-literal=AGENCY_001=$(openssl rand -base64 48) \
  -n production

# 2. Helm으로 v2.3.0 배포 (Phase 1 — 신규 기능 OFF)
helm upgrade --install ido ./infra/helm/idem-hub \
  -n production \
  -f infra/helm/idem-hub/values-prod.yaml \
  --set image.tag=2.3.0

# 3. 배포 후 Feature Flags 확인
curl -s https://ido.smes.go.kr/actuator/features | jq '.features | to_entries[] | select(.value.phase != "stable")'
```

---

## 다음 Sprint (Sprint 18 후보)

| 항목 | 우선순위 | 내용 |
|------|---------|------|
| Phase 2 게이트 통과 | 🔴 높음 | F-20 dry-run 2주 관찰 → Phase 2-B 전환 |
| mTLS 클라이언트 인증서 | 🟡 중간 | Istio/Nginx 사이드카 설정 |
| Grafana 대시보드 | 🟡 중간 | Feature Flag 상태, HMAC 에러율, 프로비저닝 처리량 |
| Phase 4 진입 결정 | 🟢 낮음 | 모든 기관 SDK 1.3.0+ 배포 확인 후 |

---

## 연관 PR

- **PR #91**: `docs/deployment-guide.md` — 56KB 운영 배포 가이드
- **PR #92**: Phase-Gate 전략 + Feature Flag F-20~F-27 + 기능별 문서
- **PR #93** (이 PR): Sprint 17 — HMAC 인프라 + Helm Chart + k6 부하 테스트
