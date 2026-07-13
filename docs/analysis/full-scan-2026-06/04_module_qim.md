# 04 · Module Deep Dive — Q-IM (회원 SoR)

> 원천: `/home/user/webapp/q-im/` · 정본 `docs/internal/spec/03c-qim-responsibility-charter.md` (헌장) + `03c-module-qim.md` (구정본).
> 파일 수 111 · MariaDB SoR · V1~V7 마이그레이션.

---

## 1. 삼각 요약

| 항목 | 값 |
|---|---|
| 포트 | 8082 |
| DB | MariaDB 11 (`qim`) — 유일한 MariaDB 사용 모듈 |
| 마이그레이션 | V1~V7 (최신 `V7__fix_datetime_timezone.sql`) |
| 파일 수 | 111 (M3 대형) |
| SoR 정본 | **회원(Member)** — 이름/CI/연락처/미성년자·후견인 |
| 정본 헌장 | `03c-qim-responsibility-charter.md` (신정본, PR #200/#201) |

---

## 2. 서브패키지 (14개)

```
q-im/src/main/java/kr/go/smes/qim/
├── QImApplication.java
├── api/             — REST 컨트롤러 진입점
├── application/     — Use case 계층
├── biz/             — 사업자·법인 회원 (미성년자 포함)
├── config/          — Bean 설정
├── consent/         — 동의 (개인정보 처리)
├── conversion/      — guest→member 전환
├── crypto/          — CI 암호화 (V3 도입)
├── domain/          — 도메인 엔티티
├── guardian/        — 미성년자 법정대리인 (V6)
├── identity/        — 신원 확인
├── infrastructure/  — 외부 클라이언트
├── outbox/          — Transactional Outbox
├── user/            — 사용자 CRUD
└── withdrawal/      — 탈퇴 (V5)
```

---

## 3. 마이그레이션 계보 V1~V7

| V | 파일 | 요지 |
|---|---|---|
| V1 | `create_schema.sql` | 회원 기본 스키마 |
| V2 | `add_idempotent_consumer.sql` | 멱등 컨슈머 |
| V3 | `add_ci_encryption_and_status_history.sql` | **CI 암호화** + 상태 이력 |
| V4 | `fix_social_sso.sql` | 소셜 SSO 회원 수정 |
| V5 | `withdrawal_consent_conversion.sql` | 탈퇴/동의/전환 |
| V6 | `minor_guardian_biz_member.sql` | 미성년자·후견인·사업자회원 |
| V7 | `fix_datetime_timezone.sql` | KST/UTC 정합 (MariaDB TZ 이슈) |

**주의**: V7 은 운영 배포 시 **MariaDB `system_time_zone=KST` vs 앱 `UTC`** 정합 이슈를 해소한 마이그레이션. NHN Cloud RDS 특성으로 발생한 것.

---

## 4. Q-IM 책임 헌장 (03c 정본 요약)

### 4.1 SoR 경계
> "Q-IM 은 **회원의 항구적 사실**만 저장한다. 세션/토큰/인가는 저장하지 않는다."

| ✅ Q-IM 저장 | ❌ Q-IM 저장 금지 |
|---|---|
| CI (암호화), 이름, 연락처, 생년월일 | 세션, JWT, Cast Token |
| 미성년자 여부, 후견인, 사업자정보 | 역할(Role), 권한(Permission) |
| 동의 이력, 탈퇴 상태 | 인증 로그(→ Q-Sign) |
| 전환 상태 (guest→member) | Handoff, SP 접속 이력 |

### 4.2 §6.5 절대 금지선 (PR #201 `8b79620`)
> "**Q-IM 은 관리자 페이지·관리자 UI 를 절대 보유하지 않는다.** 이 조항은 헌장 개정 없이는 폐기·수정할 수 없다."

**근거**: 관리자 UI를 두면 Q-IM 이 인가·세션·감사 UI 를 흡수하기 시작함. 이는 SoR 경계 침식이므로 원천 차단.

### 4.3 §7 인접 모듈 계약
| 인접 모듈 | 관계 | 방향 | 방법 |
|---|---|---|---|
| Q-Sign | 인증 결과 소비 | Q-Sign → Q-IM | Kafka `qsign.auth.events` |
| IdO | 회원 조회/전환 요청 | IdO → Q-IM | REST + HMAC |
| q-authz | 없음 | — | **직접 통신 금지** |
| onepass-support | 없음 (Q&A 익명 공존) | — | 세션만 |
| SP | 회원 이벤트 소비 | Q-IM → SP | Kafka `qim.user.events` + Outbox |
| SP → Q-IM (역방향) | SP 회원 변경 통지 | SP → Q-IM | Kafka `qim.sp.member.events` |

### 4.4 §8 거절 체크리스트 (Q-IM이 거절해야 하는 요청)
- 인가 질의 (`hasRole?`) → q-authz 로 라우팅
- 세션 조회 → IdO 로 라우팅
- 감사 로그 조회 → IdO/감사스토어로 라우팅
- Handoff 발급 → IdO 로 라우팅
- 관리자 화면 → 원천 거부 (§6.5)

---

## 5. 도메인 핵심 엔티티

| 엔티티 | 테이블 (예상) | 특성 |
|---|---|---|
| Member | `qim_member` | PK: `qim_user_id`, CI 암호화 |
| MemberStatusHistory | `qim_member_status_history` | 상태 전이 이력 (V3) |
| Consent | `qim_consent` | 동의 종류·이력 |
| MinorGuardian | `qim_minor_guardian` | 미성년자↔후견인 (V6) |
| BizMember | `qim_biz_member` | 사업자·법인 회원 (V6) |
| WithdrawalRequest | `qim_withdrawal_request` | 탈퇴 요청 (V5) |
| ConversionSession | `qim_conversion_session` | guest→member 전환 (V5) |
| CiEncryptionKey | `qim_ci_encryption_key` | CI 암호화 키 (V3) |
| Outbox | `qim_outbox` | Transactional Outbox |

---

## 6. Kafka 발행/구독

### 6.1 발행 (Q-IM Outbox → outbox-relay-batch)
- `qim.user.events` — 회원 CRUD
- `qim.user.snapshot` — 스냅샷 (SP 초기 동기화)

### 6.2 구독
- `qsign.auth.events` — 인증 완료 → Q-IM 세션 갱신 없이 감사만
- `qim.sp.member.events` — SP → Q-IM 역방향

### 6.3 6-필드 DLQ 표준 (spec 06)
DLQ 페이로드는 모두: `{event_id, original_topic, error_code, error_message, occurred_at, correlation_id}`.

---

## 7. 회색지대 (헌장 §9 관찰)

**PR #200 이전에 관찰된 회색지대** — 헌장으로 명시적으로 재정리:
1. ~~"회원 상태 조회 대시보드"~~ → 헌장 §6.5 로 금지.
2. ~~"관리자 CS 문의"~~ → **`onepass-support`** 로 이관 (PR #186 이후).
3. ~~"수동 CI 재발급"~~ → 운영 스크립트로만.
4. ~~"미성년자 후견인 관리 UI"~~ → **금지**. API 만 제공, UI 는 별도 시스템 (SP 또는 SMEs 내부 시스템)이 담당.

---

## 8. 관찰된 리스크

| # | 항목 | 위험 | 근거 |
|---|---|---|---|
| L1 | MariaDB 유일 → 재해 복구 시 이질 인프라 필요 | 🟡 MED | infra/docker/mariadb |
| L2 | V7 TZ 이슈 재발 방지 회귀 테스트 유무 | 🟡 MED | V7 목적 |
| L3 | CI 암호화 키 로테이션 정책 (V3) | 🟡 MED | V3 마이그레이션 |
| L4 | 미성년자 데이터 격리 (개인정보보호법) | 🟡 MED | V6 |
| L5 | 헌장 §6.5 위반 감시 (관리자 UI PR 자동 거절) | 🟢 LOW | 조직 프로세스 |
| L6 | q-authz 편입 후 헌장 미갱신 (`q-authz 와 상호작용 없음` 조항 필요) | 🟡 MED | 03c-charter |

---

## 9. 참조
- 소스: `/home/user/webapp/q-im/`
- 헌장 정본: `docs/internal/spec/03c-qim-responsibility-charter.md`
- 구정본: `docs/internal/spec/03c-module-qim.md`
- 관련 스펙: §05 DB, §06 Kafka
