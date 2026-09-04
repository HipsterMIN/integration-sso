# F-11 / F-11b: 개인정보 파기 스케줄러 (기존 기능)

> **환경변수**: `IDO_RETENTION_ENABLED` / `IDO_RETENTION_DRY_RUN`  
> **기본값**: `false` / `true` (매우 안전)  
> **소스**: `idem-hub/src/main/java/kr/go/smes/idem-hub/retention/PersonalDataRetentionScheduler.java`

---

## 1. 이 기능은 무엇인가?

보존 기간이 만료된 개인정보를 **자동으로 파기**하는 스케줄러입니다.

---

## 2. ⚠️ 극도의 주의 필요

이 기능은 **영구 삭제**를 실행합니다.

```
IDO_RETENTION_ENABLED=true + IDO_RETENTION_DRY_RUN=false
→ 보존 기간 초과 개인정보 DB에서 영구 삭제
→ 복구 불가능
```

---

## 3. 올바른 활성화 순서

```bash
# Step 1: dry-run으로 먼저 대상 확인 (2주 이상)
IDO_RETENTION_ENABLED=true
IDO_RETENTION_DRY_RUN=true    # 삭제 없이 로그만

# Step 2: dry-run 로그로 삭제 대상 확인
kubectl logs -n smes deployment/ido | grep "RetentionScheduler"
# 출력: [RetentionScheduler] DRY-RUN: 파기 대상 N건 (실제 삭제 안 함)

# Step 3: 법무·보안팀 승인 후 실제 파기 활성화
IDO_RETENTION_DRY_RUN=false   # 실제 삭제!

# ⚠️ Step 3는 반드시 별도 배포 티켓으로 관리
```

---

## 4. Phase-Gate와의 관계

F-11은 Sprint 14~16과 무관하게 **독립적으로 관리**됩니다.  
Phase-Gate 전략의 F-20~F-27과는 별개입니다.

---

## 연관 문서
- [배포 가이드 §11 환경변수 목록](../_archive/2026-05-22/deployment-guide.md)
