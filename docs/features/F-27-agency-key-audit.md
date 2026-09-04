# F-27: Agency API Key 인증 감사 로그 (Sprint 15)

> **환경변수**: `IDO_AGENCY_KEY_AUDIT_LOG`  
> **Phase**: 항상 ON (보안 감사 필수)  
> **기본값**: `true`  
> **소스**: `idem-hub/src/main/java/kr/go/smes/idem-hub/config/HandoffAgencyKeyInterceptor.java`

---

## 1. 이 기능은 무엇인가?

유관기관이 API를 호출할 때 **인증 시도(성공/실패)를 로그로 기록**합니다.

```
기관 요청 수신
    ↓
HandoffAgencyKeyInterceptor
    ├─ 성공: [AgencyKeyAudit] 인증 성공 agencyCode=MOIS ip=1.2.3.4
    └─ 실패: [AgencyKeyAudit] 인증 실패 agencyCode=UNKNOWN ip=5.6.7.8 reason=KEY_MISMATCH
```

---

## 2. 감사 로그 분석

```bash
# 최근 인증 실패 목록
kubectl logs -n smes deployment/ido | grep "AgencyKeyAudit.*실패"

# IP별 실패 횟수 (브루트포스 감지)
kubectl logs -n smes deployment/ido \
  | grep "AgencyKeyAudit.*실패" \
  | awk '{print $NF}' \
  | sort | uniq -c | sort -rn | head -10

# 특정 기관의 인증 이력
kubectl logs -n smes deployment/ido \
  | grep "AgencyKeyAudit" \
  | grep "agencyCode=MOIS"
```

---

## 3. 인증 실패 원인 코드

| reason | 설명 | 해결 방법 |
|--------|------|----------|
| `KEY_MISMATCH` | API Key SHA-256 불일치 | 기관에서 올바른 Key 사용 확인 |
| `AGENCY_NOT_FOUND` | DB에 기관 코드 없음 | 기관 등록 여부 확인 |
| `KEY_EXPIRED` | 키 만료 (미래 기능) | 키 재발급 |
| `MISSING_HEADER` | X-Agency-Code 또는 X-Agency-Key 헤더 없음 | 헤더 추가 |

---

## 4. 왜 끄면 안 되는가

운영 환경에서 F-27을 false로 설정하면:
- 보안 침해 시도를 감지할 수 없음
- 컴플라이언스 감사 시 인증 이력 제출 불가
- 기관 연동 문제 디버깅 어려움

> **운영에서는 항상 `IDO_AGENCY_KEY_AUDIT_LOG=true` 유지**

---

## 연관 문서
- [배포 가이드 §8.3 API Key 인증 실패](../_archive/2026-05-22/deployment-guide.md)
- [HandoffAgencyKeyInterceptor 소스](../../idem-hub/src/main/java/kr/go/smes/idem-hub/config/HandoffAgencyKeyInterceptor.java)
