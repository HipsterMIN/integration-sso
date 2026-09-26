# 관리자 인증·인가 (S7 PR-1)

> 대상: 설치자·운영자·관리 콘솔 개발자. 설계 근거는 `wiki/adr/ADR-015-admin-auth.md`, 계획은 `docs/generalization-plan.md` S7.

hub 의 관리 API(`/api/v1/admin/**`)와 actuator(`/actuator/**`, health·info·prometheus 제외), `DELETE /api/v1/handoff/{id}` 는
**관리자 세션** 뒤에 있다. 종전 `X-Admin-Id` 헤더는 아무 효력이 없다(무인증 요청은 `401 E-IDO-130`).

## 1. 모델

| 개념 | 내용 |
|---|---|
| 계정 | `idem_hub.admin_user` (V25). 사용자명·표시명·PBKDF2 비밀번호 해시·역할·테넌트·상태(ACTIVE/LOCKED/DISABLED)·봉인된 TOTP 비밀 |
| 역할 | `SYSTEM_ADMIN`(전부) · `POLICY_ADMIN`(기관·프로파일·정책 쓰기, 관리자 관리 불가) · `AUDITOR`(읽기·감사 조회만) |
| 테넌트 범위 | `tenant_code` 가 있으면 그 테넌트의 기관만 보고 만진다. `null` 이면 전역. 관리자 관리·테넌트 쓰기는 전역 `SYSTEM_ADMIN` 만 |
| 2단계 | TOTP(RFC 6238, SHA-1·6자리·30초, ±1 스텝). 비밀은 `IDEM_HUB_ADMIN_SECRET_KEY` 로 AES-256-GCM 봉인해 저장. `idem.hub.admin.mfa.required=true`(기본)면 첫 로그인에서 등록을 요구한다. **1.0.1**: 같은 스텝의 코드는 한 번만 검증된다(RFC 6238 §5.2, Redis `idem:admin:totp:{adminId}:{step}`) — 30초 안에 다시 로그인하면 `E-IDO-134` "이미 사용한 2단계 인증 코드", 다음 코드로 재시도(실패 카운터는 오르지 않는다). 역할·테넌트를 바꾸면 그 관리자의 세션은 즉시 끝난다 |
| 세션 | Redis `idem:admin:session:{sid}` — 유휴 15분·절대 8시간·동시 1(새 로그인이 이전 세션을 끝낸다). 쿠키 `idemAdminSid` HttpOnly·Secure·SameSite=Strict |
| CSRF | 모든 쓰기 요청(로그인 포함)에 `X-Requested-With` 헤더 필수 — 없으면 `403 E-IDO-131` (브라우저는 이 헤더를 교차 출처 단순 요청에 붙일 수 없다) |
| 잠금 | 비밀번호·TOTP 실패 5회 → 15분 잠금(`423 E-IDO-133`). `SYSTEM_ADMIN` 이 `unlock` 으로 즉시 해제 |
| 비밀번호 | 10자 이상, 대/소문자·숫자·특수문자 중 3종, 사용자명 포함 금지, 최근 3개 재사용 금지. 생성·재설정된 계정은 첫 로그인에서 변경 필수(`403 E-IDO-137` — 변경 전에는 `auth/**` 만 허용) |
| 감사 | 로그인 성공/실패/잠금, 2단계 등록/실패, 로그아웃, 비밀번호 변경, 관리자 생성/수정/재설정/해제, **인가 거부** 가 `idem_hub.audit_log` 에 `event_category=ADMIN`·`actor_type=ADMIN` 으로 남는다. 관리 행위(프로파일·기관·테넌트) 는 기존 감사에 인증된 사용자명이 actor 로 실린다 |
| 부트스트랩 | 관리자가 0명이고 `IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD` 가 있으면 첫 기동에서 `SYSTEM_ADMIN`(`IDEM_HUB_ADMIN_BOOTSTRAP_USERNAME`, 기본 `admin`) 을 만든다. `prod`/`stage` 에서 관리자가 없고 비밀번호도 비면 기동 거부 |

## 2. 설정 (`idem.hub.admin.*`, hub `application.yml`)

| 키 | 환경변수 | 기본 | 비고 |
|---|---|---|---|
| `bootstrap.username` | `IDEM_HUB_ADMIN_BOOTSTRAP_USERNAME` | `admin` | |
| `bootstrap.password` | `IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD` | (없음) | 관리자가 없을 때만 쓰인다. 정책을 만족해야 한다 |
| `bootstrap.require-password-change` | — | `true` | 운영 필수 true (`FailSecureBootGuard`) |
| `secret-key` | `IDEM_HUB_ADMIN_SECRET_KEY` | (없음) | base64 32바이트. TOTP 비밀 봉인. 바꾸면 모든 관리자가 2단계를 다시 등록 |
| `allow-derived-secret-key` | `IDEM_HUB_ADMIN_ALLOW_DERIVED_SECRET_KEY` | `false` | 로컬 전용: Handoff AES 키에서 유도. 운영에서 true 면 기동 거부 |
| `session.idle-minutes` / `absolute-minutes` | `IDEM_HUB_ADMIN_SESSION_IDLE_MINUTES` / `..._ABSOLUTE_MINUTES` | 15 / 480 | |
| `session.concurrent` | — | 1 | |
| `cookie.secure` | `IDEM_HUB_ADMIN_COOKIE_SECURE` | `true` | 운영 필수 true. `localhost` 는 브라우저가 안전한 문맥으로 본다 |
| `lock.max-attempts` / `duration-minutes` | — | 5 / 15 | |
| `mfa.required` | `IDEM_HUB_ADMIN_MFA_REQUIRED` | `true` | 운영 필수 true |
| `mfa.issuer` | `IDEM_HUB_ADMIN_MFA_ISSUER` | `Idem` | 인증 앱에 보이는 이름 |
| `password.min-length` / `min-classes` / `history` | — | 10 / 3 / 3 | |

## 3. API

```
POST /api/v1/admin/auth/login     {username,password}
      → {status:"OK", admin}                                  + Set-Cookie idemAdminSid   (2단계 없는 계정 — mfa.required=false 일 때만)
      → {status:"MFA_REQUIRED", mfaToken}                      인증 앱 코드로 /mfa
      → {status:"MFA_ENROLL_REQUIRED", mfaToken, secret, otpauthUri}   첫 로그인: secret 을 인증 앱에 등록하고 /mfa
POST /api/v1/admin/auth/mfa       {mfaToken, code}  → {status:"OK", admin} + Set-Cookie   (mfaToken 은 300초·1회용)
GET  /api/v1/admin/auth/me        → {adminId, username, role, tenantCode, mustChangePassword}
POST /api/v1/admin/auth/password  {currentPassword, newPassword} → 204
POST /api/v1/admin/auth/logout    → 204 (쿠키 만료)

GET  /api/v1/admin/admins · GET /{id} · POST (→201, temporaryPassword 1회 표시) · PUT /{id} {displayName?, role?, tenantCode?, status?}
POST /api/v1/admin/admins/{id}/reset-password (→ temporaryPassword) · /unlock · /reset-mfa          — 전역 SYSTEM_ADMIN 만
GET  /api/v1/admin/audit?from&to&category&action&actorId&agencyCode&outcome&correlationId&page&size(≤200)
      → {items:[{auditId, category, action, actorType, actorId, resourceType, resourceId, agencyCode, outcome, outcomeDetail, sourceIp, occurredAt, …}], page, size, total}
```

모든 쓰기 요청에 `X-Requested-With: <아무 값>` 헤더. 오류는 `{code, message, detail?}` — 코드는 §5.

curl 로 (설치 확인·CI 스모크·개발 시드가 쓰는 `scripts/lib/admin-login.sh` 가 이 흐름을 그대로 한다):

```bash
SID=$(IDEM_ADMIN_PASSWORD='…' IDEM_ADMIN_NEW_PASSWORD='…' scripts/lib/admin-login.sh)   # 첫 로그인: 2단계 등록 + 비밀번호 변경
curl http://localhost:8083/api/v1/admin/agencies -H "Cookie: idemAdminSid=$SID" -H 'X-Requested-With: cli'
```

## 4. 인가 매트릭스 (`AdminAuthorization`)

| 경로 | SYSTEM_ADMIN | POLICY_ADMIN | AUDITOR | 테넌트 범위 |
|---|---|---|---|---|
| `/api/v1/admin/auth/**` | ✅ | ✅ | ✅ | — |
| `/api/v1/admin/admins/**` | ✅ (전역만) | ❌ | ❌ | — |
| `/api/v1/admin/tenants/**` 읽기 | ✅ | ✅ | ✅ | 자기 테넌트만 |
| `/api/v1/admin/tenants/**` 쓰기 | ✅ (전역만) | ❌ | ❌ | — |
| `/api/v1/admin/audit/**` | ✅ | ✅ | ✅ | 테넌트 관리자는 `agencyCode` 필수(자기 기관) |
| `/api/v1/admin/**` 그 밖의 GET | ✅ | ✅ | ✅ | 목록은 범위 밖 기관 제외, 단건은 `403` |
| `/api/v1/admin/**` 그 밖의 쓰기 (프로파일 PUT·기관 활성화·키 회전·OIDC secret …) | ✅ | ✅ | ❌ | 범위 밖 기관 `403`; 새 프로파일의 `service.tenant` 도 범위 안이어야 한다 |
| `DELETE /api/v1/handoff/{id}` | ✅ | ✅ | ❌ | — |
| `/actuator/**` (health·info·prometheus 제외) | ✅ | ❌ | ❌ | — |

거부는 `403 E-IDO-131` 이고 `ADMIN_ACCESS_DENIED` 로 감사된다. 비밀번호 변경이 필요한 세션은 `auth/**` 외 전부 `403 E-IDO-137`.

## 5. 오류 코드

| 코드 | HTTP | 뜻 |
|---|---|---|
| `E-IDO-130` | 401 | 관리자 세션 없음·만료 |
| `E-IDO-131` | 403 | 권한 없음 · CSRF 헤더 없음 · 테넌트 범위 밖 |
| `E-IDO-132` | 401 | 로그인 실패(사용자명·비밀번호·현재 비밀번호 불일치 — 어느 쪽인지 말하지 않는다) |
| `E-IDO-133` | 423 | 계정 잠김(실패 5회 → 15분) 또는 비활성 |
| `E-IDO-134` | 401 | 2단계 실패·대기 토큰 만료/재사용·같은 TOTP 스텝 재사용(1.0.1) |
| `E-IDO-135` | 400 | 비밀번호 정책 위반(`detail` 에 사유) |
| `E-IDO-136` | 409 | 마지막 SYSTEM_ADMIN 을 강등·비활성화할 수 없음 |
| `E-IDO-137` | 403 | 첫 로그인 비밀번호 변경 필요 |
| `E-IDO-138` | 404 | 관리자 없음 |
| `E-IDO-139` | 409 | 사용자명 중복 |

## 6. 운영

- **2단계 비밀을 잃은 관리자**: 다른 `SYSTEM_ADMIN` 이 `POST /admins/{id}/reset-mfa` → 다음 로그인에서 재등록. 마지막 `SYSTEM_ADMIN` 이 잃었으면 DB 에서 `UPDATE idem_hub.admin_user SET totp_secret_enc=NULL, totp_enrolled=false WHERE username='…'` (감사 로그에 수기 기록).
- **잠긴 관리자**: 15분 뒤 자동 해제 또는 `POST /admins/{id}/unlock`.
- **`IDEM_HUB_ADMIN_SECRET_KEY` 교체**: 모든 관리자의 2단계를 `reset-mfa` 로 초기화한 뒤 키를 바꾼다(봉인된 비밀은 옛 키로만 열린다).
- **세션 강제 종료**: 관리자를 `status=DISABLED`/`LOCKED` 로 바꾸거나 비밀번호를 재설정하면 그 관리자의 세션이 즉시 끝난다.
- 관리 콘솔 `idem-console-admin/`(S7 PR-2)이 이 API 위에 있다 — 쿠키는 브라우저가 들고, `X-Requested-With` 는 콘솔이 붙인다. nginx 가 `/api/v1/admin/` 만 hub 로 프록시하므로 콘솔 출처에서 CORS 는 필요 없다.
