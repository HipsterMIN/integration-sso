# OPS-004 기관별 K8s Secret 등록 가이드

> **대상**: 운영팀 / DevOps  
> **분류**: 운영 절차서  
> **최종 갱신**: 2026-05-17 (Sprint 18, M-03)  
> **관련 ADR**: ADR-013, ADR-010

---

## 1. 개요

OnePass 통합인증 플랫폼은 유관기관과의 연동 자격증명(API Key, HMAC Secret, mTLS 인증서)을  
**K8s Secret** 으로 관리하고, `outbox-relay-batch` Pod에 `envFrom` 방식으로 주입한다.

```
K8s Secret
  └─ envFrom → outbox-relay-batch Pod
       └─ ProvisioningRelayJob.findSecret(ref)
            ├─ System.getenv(envVarName)   ← K8s Secret (우선)
            └─ ido.agency_credential_config ← DB Fallback
```

**환경변수명 변환 규칙**:  
`authCredentialRef`의 비알파벳/숫자 → `_` 대문자화  
예) `secrets/agency/AGENCY_001/api-key` → `SECRETS_AGENCY_AGENCY_001_API_KEY`

---

## 2. 사전 확인 체크리스트

기관 Secret 등록 전 아래 항목을 확인한다.

```
[ ] ido.agency_endpoint_registry 에 기관 레코드 존재 확인
    SELECT * FROM ido.agency_endpoint_registry WHERE agency_code = '<기관코드>';

[ ] auth_type, auth_credential_ref 값 확인
[ ] K8s 네임스페이스 및 배포 환경 확인 (production / staging)
[ ] Secret 이름 중복 여부 확인
    kubectl get secret -n production | grep <기관코드>
```

---

## 3. 인증 방식별 Secret 등록

### 3-1. API_KEY 방식

```bash
# authCredentialRef 예시: secrets/agency/AGENCY_001/api-key
# 환경변수명:             SECRETS_AGENCY_AGENCY_001_API_KEY

kubectl create secret generic agency-credential-agency001 \
  --from-literal=SECRETS_AGENCY_AGENCY_001_API_KEY='<발급된-API-KEY>' \
  -n production

# 검증
kubectl get secret agency-credential-agency001 -n production -o jsonpath='{.data.SECRETS_AGENCY_AGENCY_001_API_KEY}' \
  | base64 --decode
```

### 3-2. HMAC 방식

```bash
# authCredentialRef 예시: secrets/agency/AGENCY_003/hmac-secret
# 환경변수명:             SECRETS_AGENCY_AGENCY_003_HMAC_SECRET

kubectl create secret generic agency-credential-agency003 \
  --from-literal=SECRETS_AGENCY_AGENCY_003_HMAC_SECRET='<HMAC-공유-비밀키>' \
  -n production
```

### 3-3. MTLS 방식

mTLS는 헤더 자격증명 없이 클라이언트 인증서(PKCS12)로 TLS 핸드셰이크 처리.  
인증서는 `outbox-relay-batch` 공용 Secret에 등록.

```bash
# PKCS12 KeyStore → Base64 인코딩
KEYSTORE_B64=$(base64 -w0 /path/to/client-cert.p12)

# 배치 mTLS 인증서 Secret 등록 (전체 배치 공용 — 기관별 아님)
kubectl create secret generic batch-mtls-cert \
  --from-literal=BATCH_MTLS_KEYSTORE_BASE64="$KEYSTORE_B64" \
  --from-literal=BATCH_MTLS_KEYSTORE_PASS='<keystore-password>' \
  -n production

# 인증서 만료일 확인 (openssl)
echo "$KEYSTORE_B64" | base64 --decode \
  | openssl pkcs12 -nokeys -passin pass:'<keystore-password>' 2>/dev/null \
  | openssl x509 -noout -enddate
```

### 3-4. NONE 방식

인증 없음 — Secret 등록 불필요.  
`ido.agency_endpoint_registry.auth_credential_ref` 컬럼은 NULL 또는 빈 문자열.

---

## 4. outbox-relay-batch Deployment에 envFrom 추가

기관 Secret을 신규 등록한 후 `outbox-relay-batch` Deployment의 `envFrom`에 추가해야 Pod이 주입받는다.

```yaml
# k8s/outbox-relay-batch-deployment.yaml 중 envFrom 섹션
spec:
  template:
    spec:
      containers:
        - name: outbox-relay-batch
          envFrom:
            - secretRef:
                name: batch-common-secrets          # Kafka, Redis, DB 접속 정보
            - secretRef:
                name: batch-mtls-cert               # mTLS 인증서 (MTLS 기관 공용)
            - secretRef:
                name: batch-alert-slack             # DEAD_LETTER Slack 알림
            - secretRef:
                name: batch-alert-pagerduty         # DEAD_LETTER PagerDuty 알림
            # ── 기관별 자격증명 ──────────────────────────────────────
            - secretRef:
                name: agency-credential-agency001   # AGENCY_001 API Key
            - secretRef:
                name: agency-credential-agency003   # AGENCY_003 HMAC Secret
            # (신규 기관 추가 시 여기에 append)
```

```bash
# Deployment 패치로 신규 Secret 즉시 반영 (Pod 재기동 없이)
kubectl patch deployment outbox-relay-batch -n production \
  --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/envFrom/-",
        "value":{"secretRef":{"name":"agency-credential-<신규기관코드>"}}}]'

# 패치 후 rollout 확인
kubectl rollout status deployment/outbox-relay-batch -n production
```

---

## 5. DEAD_LETTER 알림 채널 Secret

`outbox-relay-batch`의 D-06 DEAD_LETTER 알림 기능 활성화에 필요한 Secret.

### 5-1. Slack Incoming Webhook

```bash
# 1. Slack 앱 설정: https://api.slack.com/apps → Incoming Webhooks 활성화
# 2. 채널별 Webhook URL 발급 (예: #onepass-alerts)

kubectl create secret generic batch-alert-slack \
  --from-literal=BATCH_ALERT_SLACK_WEBHOOK_URL='https://hooks.slack.com/services/T.../B.../...' \
  -n production

# 활성화 환경변수 (application.yml 오버라이드)
kubectl patch configmap outbox-relay-batch-config -n production \
  --patch '{"data":{"BATCH_ALERT_SLACK_ENABLED":"true"}}'
```

### 5-2. PagerDuty Events API v2

```bash
# 1. PagerDuty: Services → Integrations → Events API v2 추가
# 2. Integration Key (Routing Key) 복사

kubectl create secret generic batch-alert-pagerduty \
  --from-literal=BATCH_ALERT_PAGERDUTY_ROUTING_KEY='<integration-key>' \
  -n production

kubectl patch configmap outbox-relay-batch-config -n production \
  --patch '{"data":{"BATCH_ALERT_PAGERDUTY_ENABLED":"true"}}'
```

---

## 6. 등록 후 검증 절차

```bash
# 1. Secret 등록 확인
kubectl get secret -n production | grep agency-credential

# 2. Pod 환경변수 주입 확인 (Secret 값 직접 출력 금지 — 환경변수 이름만 확인)
kubectl exec -n production \
  $(kubectl get pod -n production -l app=outbox-relay-batch -o name | head -1) \
  -- env | grep SECRETS_AGENCY

# 3. 배치 로그에서 자격증명 조회 성공 확인 (WARN 없으면 정상)
kubectl logs -n production \
  $(kubectl get pod -n production -l app=outbox-relay-batch -o name | head -1) \
  --since=5m | grep -E "(credential|DEAD_LETTER|K8s Secret)"

# 4. Prometheus 메트릭으로 dead_letter 카운터 확인
curl -s http://<batch-pod-ip>:8090/actuator/metrics/batch.relay.provisioning.dead_letter \
  | jq '.measurements[0].value'
```

---

## 7. 기관별 Secret 등록 현황 추적표

> 운영팀이 직접 관리. 기관 추가/변경 시 업데이트.

| 기관코드 | auth_type | Secret 이름 | 환경변수명 | 등록일 | 만료일 | 담당자 |
|----------|-----------|-------------|-----------|--------|--------|--------|
| AGENCY_001 | API_KEY | agency-credential-agency001 | SECRETS_AGENCY_AGENCY_001_API_KEY | - | - | - |
| AGENCY_002 | MTLS | batch-mtls-cert (공용) | BATCH_MTLS_KEYSTORE_BASE64 | - | - | - |
| AGENCY_003 | HMAC | agency-credential-agency003 | SECRETS_AGENCY_AGENCY_003_HMAC_SECRET | - | - | - |

---

## 8. Secret 교체(로테이션) 절차

```bash
# 기존 Secret 값 교체 (kubectl apply가 아닌 --from-literal로 patch)
kubectl create secret generic agency-credential-agency001 \
  --from-literal=SECRETS_AGENCY_AGENCY_001_API_KEY='<신규-API-KEY>' \
  --dry-run=client -o yaml | kubectl apply -f -

# Pod 재기동 없이 반영 (envFrom은 Secret 변경 시 자동 반영 안 됨 → rollout 필요)
kubectl rollout restart deployment/outbox-relay-batch -n production
kubectl rollout status deployment/outbox-relay-batch -n production
```

> ⚠️ **주의**: K8s Secret은 etcd에 base64로 저장됨. 운영 클러스터에서 etcd 암호화(EncryptionConfiguration)  
> 적용 여부를 반드시 확인할 것. 미적용 시 Secret 평문 노출 위험.

---

## 9. 관련 문서

| 문서 | 경로 |
|------|------|
| 운영 배포 가이드 | `wiki/ops/01-production-deployment-guide.md` |
| 기관 지원 런북 | `wiki/ops/03-agency-support-runbook.md` |
| ADR-013 Java Agent 마이그레이션 | `wiki/adr/ADR-013-java-agent-migration.md` |
| DEAD_LETTER 알림 구현 | `outbox-relay-batch/.../alert/DeadLetterNotifier.java` |
