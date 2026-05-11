# 회원 수정 · 탈퇴 데이터 흐름 (A→Z 완전 추적)

**문서 ID**: FLOW-2026-003  
**버전**: v1.0  
**작성일**: 2026-05-11  
**작성자**: AI 분석 (GenSpark)  
**대상 독자**: 개발팀, 운영팀, 보안팀

---

## 목차

1. [마이페이지 구조 개요](#1-마이페이지-구조-개요)
2. [회원 정보 수정 흐름 (A→Z)](#2-회원-정보-수정-흐름-az)
3. [회원 탈퇴 흐름 (A→Z)](#3-회원-탈퇴-흐름-az)
4. [비밀번호 변경 흐름 (A→Z)](#4-비밀번호-변경-흐름-az)
5. [소속 기관 관리 흐름 (A→Z)](#5-소속-기관-관리-흐름-az)
6. [DB 변경 전체 목록](#6-db-변경-전체-목록)
7. [Kafka 이벤트 발행 목록](#7-kafka-이벤트-발행-목록)
8. [세션 무효화 처리](#8-세션-무효화-처리)
9. [미구현 항목 및 주의사항](#9-미구현-항목-및-주의사항)

---

## 1. 마이페이지 구조 개요

### 1.1 라우트 구조

```
/mypage/member/            → MypageMember (개인 마이페이지 레이아웃)
  /information             → Information (나의 정보 조회)
  /information/step2       → InformationStep2 (정보 수정 — 인증)
  /information/step3       → InformationStep3 (정보 수정 — 저장)
  /password/step1          → PasswordStep1 (비밀번호 변경)
  /affiliation             → Affiliation (소속 기관 목록)
  /affiliation/add/step1   → AffiliationAddStep1
  /affiliation/add/step2   → AffiliationAddStep2
  /affiliation/withdraw/step1 → AffiliationWithdrawStep1
  /affiliation/withdraw/step2 → AffiliationWithdrawStep2
  /withdraw                → Withdraw (탈퇴 안내 step1)
  /withdraw/step2          → WithdrawStep2 (탈퇴 인증)
  /withdraw/complete       → WithdrawComplete (탈퇴 완료)

/mypage/business/          → MypageBusiness (기업 마이페이지 레이아웃)
  (동일 하위 구조)
```

### 1.2 인증 요구사항

마이페이지 라우트는 `isPrivate: true` — `feSessionId` 쿠키 필수.  
세션 없으면 `/unauthorized` 페이지로 리다이렉트.

### 1.3 세션 체크 흐름

```
FE 마이페이지 진입
  ↓
GET /api/v1/fe-session/check (withCredentials: true)
  → 200: 세션 유효 → 마이페이지 표시
  → 401: 세션 없음/만료 → /login으로 리다이렉트
```

**API 파일**: `onepass-fe/frontend/src/api/feSession.ts`

---

## 2. 회원 정보 수정 흐름 (A→Z)

### 2.1 정보 조회 (Information.tsx)

```
[A] 사용자 마이페이지 접속
    FE: feSessionId 쿠키 자동 포함

[B] 회원 정보 표시 (useInfoStore 훅)
    FE: 레거시 외부 API 또는 provision API 호출
    표시 정보:
      - 아이디 (수정 불가)
      - 이름 (수정 불가)
      - 휴대전화번호 (수정 가능)
      - 이메일 (수정 가능)
    ※ 알림 수신 설정은 현재 주석 처리됨 (미구현)

[C] 사용자 [정보변경] 클릭
    FE: history.push('/mypage/member/information/step2')
```

**현재 상태**: `Information.tsx`의 모든 입력 필드가 `disabled` / `readOnly` 상태.  
조회 전용으로만 동작 중. 수정은 Step2에서 진행.

### 2.2 정보 수정 흐름 (InformationStep2 → Step3)

```
[A] Step2 진입 — 본인인증 (재인증)
    본인인증 방법:
      - NICE 휴대폰 인증 (새 번호 확인)
      - OACX 간편인증서

[B] 본인인증 완료
    → 인증 토큰/CI 임시 보관

[C] Step3 — 변경 정보 입력
    변경 가능 항목:
      - 휴대전화번호
      - 이메일
      - 알림 수신 설정

[D] FE → 외부 API (레거시 SMEP 연동):
    PATCH /api/v1/ext/members/{memberId}
    or
    PUT /api/v1/provision/users/{qimUserId}
    Body:
    {
      "phone": "010-1234-5678",
      "email": "user@example.com",
      "ci": "...",     ← 재인증된 CI (서버에서만 처리)
      "notifications": { "sms": true, "email": false }
    }

[E] 백엔드 처리:
    1. feSessionId 세션 검증
    2. Q-IM: PATCH /api/v1/internal/users/{qimUserId}/status
       또는 전용 프로필 업데이트 API (현재 미구현)
    3. Outbox: UserEvent(TYPE_UPDATED) 적재
    4. FeSession advisory flag 갱신 (필요 시)

[F] 수정 완료
    → Information 페이지로 이동 (갱신된 정보 표시)
```

> ⚠️ **현재 상태**: Q-IM 직접 프로필 수정 API (`PATCH /api/v1/internal/users/{id}/profile`)가 미구현.  
> `UserController`에는 상태 변경(`/status`)만 있음. 이름/전화번호/이메일 변경 API 추가 필요.

### 2.3 수정 시 Q-IM DB 변경

```sql
-- Q-IM user_profile 업데이트 (현재 미구현, 향후 추가 필요)
UPDATE user_profile
SET mobile_masked = ?,   -- "010****5678"
    updated_at = NOW(6)
WHERE qim_user_id = ?;

-- user_status_history 기록
INSERT INTO user_status_history
(qim_user_id, status_before, status_after, changed_by, change_reason, occurred_at)
VALUES (?, 'ACTIVE', 'ACTIVE', 'USER', 'PROFILE_UPDATED', NOW(6));

-- Outbox 적재
INSERT INTO outbox (event_type, payload, created_at)
VALUES ('USER_UPDATED', '{"qimUserId":"...","reason":"PROFILE_UPDATED"}', NOW(6));
```

---

## 3. 회원 탈퇴 흐름 (A→Z)

### 3.1 전체 흐름

```
사용자      FE(React)          ido(8083)           q-im(8082)          Kafka
  │             │                  │                   │                   │
  │ [A] 탈퇴    │                  │                   │                   │
  │ 메뉴 클릭   │                  │                   │                   │
  │────────────>│                  │                   │                   │
  │             │ [B] Withdraw.tsx │                   │                   │
  │             │ 탈퇴 안내 + 보관│                   │                   │
  │             │ 정보 표시        │                   │                   │
  │             │                  │                   │                   │
  │ [C] 다음    │                  │                   │                   │
  │ 클릭        │                  │                   │                   │
  │────────────>│                  │                   │                   │
  │             │ [D] "서비스 준비 │                   │                   │
  │             │ 중" 모달 표시    │                   │                   │
  │             │ (개발 중)        │                   │                   │
  │             │                  │                   │                   │
  │   ┌──── 향후 구현 예정 ────────────────────────────────────────────────┐
  │   │         │                  │                   │                   │
  │   │         │ [E-미래] Step2   │                   │                   │
  │   │         │ 본인인증 (재확인)│                   │                   │
  │   │         │                  │                   │                   │
  │   │         │ [F-미래] 탈퇴    │                   │                   │
  │   │         │ 사유 입력        │                   │                   │
  │   │         │                  │                   │                   │
  │   │         │ [G-미래] 최종    │                   │                   │
  │   │         │ 확인             │                   │                   │
  │   │         │                  │                   │                   │
  │   │         │ [H-미래] DELETE  │                   │                   │
  │   │         │ /api/v1/internal/│                   │                   │
  │   │         │ users/{qimUserId}│                   │                   │
  │   │         │─────────────────────────────────────>│                   │
  │   │         │                  │                   │ [I-미래]          │
  │   │         │                  │                   │ 탈퇴 처리:        │
  │   │         │                  │                   │ status=WITHDRAWN  │
  │   │         │                  │                   │ withdrawnAt=now() │
  │   │         │                  │                   │ PII 즉시 삭제     │
  │   │         │                  │                   │ Outbox 적재       │
  │   │         │                  │                   │─────────────────>│
  │   │         │ [J-미래] FE 세션 │                   │                   │
  │   │         │ 일괄 무효화      │                   │                   │
  │   │         │ SLO 처리         │                   │                   │
  │   │         │                  │                   │                   │
  │   │         │ [K-미래] 탈퇴    │                   │                   │
  │   │         │ 완료 페이지      │                   │                   │
  │   └──────────────────────────────────────────────────────────────────┘
```

### 3.2 현재 구현 상태 (Withdraw.tsx)

```typescript
// Withdraw.tsx — 현재 "다음" 버튼은 "서비스 준비 중" 모달만 표시
<button type="button" className="btn point" 
  onClick={(): void => setDevNoticeModal(true)}>
  <span>다음</span>
</button>

// TODO: API 배포 후 복원 — goNext 로 다음 단계 이동
// const nextRoute = getMypageRoute(memberType, 'WITHDRAW_STEP2');
```

**현재 탈퇴 기능 완전 미구현** — "서비스 준비 중" 모달만 표시됨.

### 3.3 향후 구현 시 탈퇴 처리 상세 (Q-IM 기준)

**파일**: `q-im/src/main/java/kr/go/smes/qim/user/UserRegistrationServiceImpl.java`

```java
// UserRegistrationServiceImpl.withdraw()
@Transactional
public void withdraw(String qimUserId, String reason) {
    
    // 1. 상태 변경
    QimUserJpaEntity user = userRepository.findById(qimUserId).orElseThrow(...);
    String oldStatus = user.getStatus();
    user.setStatus("WITHDRAWN");
    user.setWithdrawnAt(Instant.now());
    user.setWithdrawalReason(reason);
    userRepository.save(user);

    // 2. PII 즉시 삭제 (GDPR §17 Right to be Forgotten)
    jdbcTemplate.update("""
        UPDATE user_profile
        SET name_masked = NULL,
            mobile_masked = NULL,
            ci = NULL,
            di_map = NULL,
            extra_attributes = NULL,
            updated_at = NOW(6)
        WHERE qim_user_id = ?
        """, qimUserId);
    // auth_mean_mapping의 identifierHash는 유지 (탈퇴 이력 보존, 원문 복원 불가)

    // 3. 상태 이력 기록
    jdbcTemplate.update("""
        INSERT INTO user_status_history
        (qim_user_id, status_before, status_after, changed_by, change_reason, occurred_at)
        VALUES (?, ?, 'WITHDRAWN', 'SYSTEM', ?, NOW(6))
        """, qimUserId, oldStatus, reason);

    // 4. Kafka Outbox 적재
    outboxService.publishInTx(new UserEvent(
        UserEvent.TYPE_WITHDRAWN, "q-im",
        null, qimUserId, user.getEventVersion() + 1,
        "WITHDRAWN", reason, true));
}
```

### 3.4 탈퇴 처리 시 전체 데이터 흐름

```
탈퇴 요청 수신 (ido)
  ↓
1. feSessionId 세션 검증
2. 재인증 확인 (본인 확인)
  ↓
Q-IM: DELETE /api/v1/internal/users/{qimUserId}?reason=USER_REQUEST
  ↓
q-im: @Transactional {
    status = WITHDRAWN
    withdrawnAt = now
    PII NULL 처리 (name_masked, mobile_masked, ci, di_map)
    user_status_history INSERT
    outbox INSERT (UserEvent TYPE_WITHDRAWN)
}
  ↓
ido: FE 세션 일괄 무효화
  FeSessionService.invalidateByQimUserId(qimUserId, "USER_WITHDRAWAL")
  → Redis: fe:session:* 삭제
  → Redis: fe:user-sessions:{qimUserId} 삭제
  ↓
ido: SLO 처리 (Keycloak 세션 종료)
  ↓
ido: 기관 탈퇴 Webhook Outbox 적재
  → Relay가 등록된 기관 Webhook URL에 POST
  ↓
FE: feSessionId 쿠키 삭제 (Set-Cookie: Max-Age=0)
탈퇴 완료 페이지 표시
  ↓
Kafka Relay: qim.user.events에 TYPE_WITHDRAWN 이벤트 발행
  → 구독 서비스들이 탈퇴 처리 (e.g., 기관 회원 연동 해제)
```

### 3.5 탈퇴 후 데이터 보존 정책

| 데이터 | 처리 | 보존 기간 |
|---|---|---|
| 이름 (name_masked) | 즉시 NULL | 즉시 파기 |
| 전화번호 (mobile_masked) | 즉시 NULL | 즉시 파기 |
| CI 암호화 (ci) | 즉시 NULL | 즉시 파기 |
| DI 맵 (di_map) | 즉시 NULL | 즉시 파기 |
| identifierHash | 유지 | 영구 (재가입 방지 가능) |
| qimUserId | 유지 (status=WITHDRAWN) | 영구 |
| withdrawnAt | 유지 | 영구 |
| 지원사업 신청 이력 | 유지 | 5년 |
| 증명서 발급 이력 | 유지 | 180일 |
| 전자민원 신청 이력 | 유지 | 180일 |

> **FE 안내 문구** (Withdraw.tsx 탈퇴 안내 테이블에서 확인됨)

---

## 4. 비밀번호 변경 흐름 (A→Z)

**파일**: `onepass-fe/frontend/src/pages/Mypage/pages/PasswordStep1.tsx`

```
[A] 마이페이지 → 비밀번호 변경 클릭
    → /mypage/member/password/step1

[B] PasswordStep1 — 현재 비밀번호 + 새 비밀번호 입력
    유효성 검사:
      - 현재 비밀번호 확인
      - 새 비밀번호: 8자 이상, 영문+숫자+특수문자 조합
      - 새 비밀번호 확인 일치

[C] FE → Keycloak 비밀번호 변경 API
    (Keycloak Account REST API 또는 SMEP 레거시 API 경유)
    
    경로 1 (Keycloak 직접):
      PUT https://keycloak/realms/sso/account/credentials/password
      Authorization: Bearer {access_token}
      Body: { currentPassword, newPassword }
    
    경로 2 (ido BFF):
      PUT /api/v1/mypage/password
      feSessionId 쿠키 포함
      Body: { currentPassword, newPassword }
      ido → Keycloak Admin API 호출

[D] 변경 성공 시:
    - FE 세션 일괄 무효화 (보안 강화)
      FeSessionService.invalidateByQimUserId(qimUserId, "PASSWORD_CHANGED")
    - 현재 세션 재발급 (재로그인 없이 유지)
    - Advisory 플래그 해제

[E] 완료 → 로그인 페이지로 이동 (재로그인 필요)
    또는 세션 유지 후 마이페이지 유지 (정책 결정 필요)
```

> ⚠️ **현재 상태**: `PasswordStep1.tsx` 존재하나 구체적인 API 연동 구현 여부 확인 필요.  
> Keycloak 비밀번호 변경 후 FE 세션 처리 정책 미확정.

---

## 5. 소속 기관 관리 흐름 (A→Z)

### 5.1 소속 기관 목록 조회

```
[A] 마이페이지 → 소속 기관 클릭
    → /mypage/member/affiliation

[B] FE → API 호출
    GET /api/v1/provision/affiliations
    또는
    GET /api/v1/ext/affiliations
    feSessionId 쿠키 포함

[C] 현재 소속 기관 목록 표시
    { agencyCode, agencyName, status, linkedAt }[]
```

### 5.2 소속 기관 추가

```
[A] [기관 추가] 클릭 → AffiliationAddStep1
    → 기관 검색 (기관명/기관코드)

[B] 기관 선택 → AffiliationAddStep2
    → 추가 확인

[C] FE → API
    POST /api/v1/provision/affiliations
    Body: { agencyCode }
    
    처리:
    - 기관 유효성 검증 (AgencyMetaRepository)
    - 중복 소속 확인
    - 소속 관계 생성
```

### 5.3 소속 기관 탈퇴

```
[A] 기관 탈퇴 클릭 → AffiliationWithdrawStep1
    → 탈퇴 안내 표시

[B] 확인 → AffiliationWithdrawStep2
    → 탈퇴 사유 입력

[C] FE → API
    DELETE /api/v1/provision/affiliations/{agencyCode}
    
    처리:
    - 소속 관계 삭제
    - 해당 기관 Webhook 알림 (탈퇴 이벤트)
    - Kafka 이벤트 발행
```

---

## 6. DB 변경 전체 목록

### 회원 정보 수정 시

| 테이블 | 작업 | 내용 | 구현 상태 |
|---|---|---|---|
| `user_profile` | UPDATE | mobile_masked, updated_at | ⚠️ API 미구현 |
| `user_status_history` | INSERT | reason=PROFILE_UPDATED | ⚠️ API 미구현 |
| `outbox` | INSERT | eventType=USER_UPDATED | ⚠️ API 미구현 |

### 회원 탈퇴 시

| 테이블 | 작업 | 내용 | 구현 상태 |
|---|---|---|---|
| `qim_user` | UPDATE | status=WITHDRAWN, withdrawnAt, withdrawalReason | ✅ 구현됨 (미연동) |
| `user_profile` | UPDATE | 모든 PII 컬럼 NULL | ✅ 구현됨 (미연동) |
| `user_status_history` | INSERT | status_before→WITHDRAWN | ✅ 구현됨 (미연동) |
| `outbox` | INSERT | eventType=USER_WITHDRAWN | ✅ 구현됨 (미연동) |

> **구현됨 (미연동)**: Q-IM 서비스 코드는 완성되었으나 FE → ido → Q-IM 연결 미완성.

---

## 7. Kafka 이벤트 발행 목록

| 토픽 | 이벤트 타입 | 발행 시점 | 내용 |
|---|---|---|---|
| `qim.user.events` | `USER_UPDATED` | 프로필 수정 완료 | qimUserId, reason=PROFILE_UPDATED, needsSync=true |
| `qim.user.events` | `USER_SUSPENDED` | 상태 변경(정지) | qimUserId, newStatus, reason |
| `qim.user.events` | `USER_WITHDRAWN` | 탈퇴 처리 완료 | qimUserId, reason=USER_REQUEST, needsSync=true |
| `ido.handoff.events` | `HANDOFF_ISSUED` | Handoff 티켓 발급 | ticketId, agencyCode, qimUserId |

### UserEvent 구조 (탈퇴)

```json
{
  "eventType": "USER_WITHDRAWN",
  "sourceService": "q-im",
  "correlationId": null,
  "qimUserId": "uuid-v7",
  "eventVersion": 2,
  "newStatus": "WITHDRAWN",
  "reason": "USER_REQUEST",
  "needsSync": true
}
```

`needsSync=true`: 소비자(ido 등)가 캐시된 사용자 정보를 즉시 갱신해야 함을 표시.

---

## 8. 세션 무효화 처리

### 탈퇴/보안 이벤트 시 세션 처리

```java
// FeSessionServiceImpl.invalidateByQimUserId()
void invalidateByQimUserId(String qimUserId, String reason) {
    
    // 1. 사용자의 모든 세션 ID 조회
    Set<Object> sessionIds = redisTemplate.opsForSet()
        .members("fe:user-sessions:" + qimUserId);
    
    // 2. 각 세션 삭제
    for (Object sid : sessionIds) {
        redisTemplate.delete("fe:session:" + sid);
    }
    
    // 3. 역인덱스 삭제
    redisTemplate.delete("fe:user-sessions:" + qimUserId);
    
    log.warn("[FeSession] MANDATORY 일괄 무효화 qimUserId={} count={} reason={}",
             qimUserId, sessionIds.size(), reason);
}
```

### Advisory 플래그 설정 (소프트 경고)

비밀번호 변경 권고, 의심 활동 감지 등 즉각 강제 로그아웃이 아닌 경우:

```java
// FeSessionServiceImpl.markAdvisoryFlag()
void markAdvisoryFlag(String qimUserId, String reason) {
    // 사용자의 모든 활성 세션에 advisoryFlag=true 설정
    // FE는 다음 API 응답에서 advisoryFlag=true 감지 시 안내 UI 표시
    // 기존 TTL 유지 (강제 로그아웃 아님)
}
```

---

## 9. 미구현 항목 및 주의사항

### 9.1 탈퇴 기능 (최우선 구현 필요)

| 항목 | 상태 | 필요 작업 |
|---|---|---|
| `Withdraw.tsx` 다음 버튼 | 개발 중 모달만 표시 | WithdrawStep2 인증 흐름 구현 |
| `WithdrawStep2.tsx` 본인인증 | 파일 존재, 구현 미완 | 재인증 → 탈퇴 API 연결 |
| `DELETE /api/v1/internal/users/{id}` | Q-IM 구현됨 | ido → Q-IM 연결 코드 필요 |
| FE 세션 무효화 (탈퇴 시) | Q-IM 이벤트 미구독 | Kafka 이벤트 소비자 구현 |
| Webhook 알림 (탈퇴) | HandoffServiceImpl 부분 구현 | 기관별 Webhook 전송 테스트 필요 |

### 9.2 회원 수정 기능

| 항목 | 상태 | 필요 작업 |
|---|---|---|
| Q-IM 프로필 업데이트 API | 미구현 | `PATCH /api/v1/internal/users/{id}/profile` 추가 |
| 알림 수신 설정 | 주석 처리 | UI + API 연동 구현 |
| 이메일 변경 | UI만 존재 | 인증 후 변경 API 구현 |

### 9.3 보안 고려사항

| 항목 | 위험도 | 내용 |
|---|---|---|
| 탈퇴 시 identifierHash 유지 | 중 | 재가입 방지 목적이나 정책 문서화 필요 |
| PII 삭제 감사 로그 부재 | 중 | GDPR 준수 증명을 위한 감사 로그 필요 |
| 탈퇴 후 기관 세션 연동 해제 | 고 | Webhook 실패 시 재시도 메커니즘 필요 |

---

*문서 끝 — FLOW-2026-003 v1.0*
