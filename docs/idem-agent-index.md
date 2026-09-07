# OnePass Agency Java Agent — 문서 인덱스

> **최종 수정**: 2026-05-17  
> **버전**: v1.0.0

---

## 📚 문서 목록

### 👥 유관기관 개발자 / 시스템 관리자 대상

| 문서 번호 | 문서명 | 경로 | 대상 독자 |
|---------|-------|------|---------|
| AGENT-GUIDE-001 | **유관기관 개발자 통합 가이드** | [`idem-agent-integration-guide.md`](./idem-agent-integration-guide.md) | 유관기관 개발자, 시스템 관리자 |
| AGENT-WALKTHROUGH-001 | **설치·운영 워크스루** | [`idem-agent-walkthrough.md`](./idem-agent-walkthrough.md) | 처음 설치하는 담당자 |
| AGENT-TROUBLESHOOT-001 | **트러블슈팅 가이드** | [`idem-agent-troubleshooting.md`](./idem-agent-troubleshooting.md) | 유관기관 담당자, 지원팀 |

### 🔧 OnePass 플랫폼 내부 개발자 대상

| 문서 번호 | 문서명 | 경로 | 대상 독자 |
|---------|-------|------|---------|
| AGENT-ARCH-001 | **에이전트 아키텍처 설계서** | [`internal/architecture/idem-agent-architecture.md`](./internal/architecture/idem-agent-architecture.md) | 아키텍트, 시니어 개발자 |
| AGENT-DEVREF-001 | **내부 개발자 기술 레퍼런스** | [`internal/development/idem-agent-developer-reference.md`](./internal/development/idem-agent-developer-reference.md) | OnePass 팀 개발자 |
| AGENT-JEUS-001 | **JEUS 버전별 SSO 심층 기술 노트** | [`internal/architecture/jeus-sso-deep-dive.md`](./internal/architecture/jeus-sso-deep-dive.md) | 아키텍트, 시니어 개발자 |

---

## 🗺️ 문서 읽기 순서

### 처음 설치하는 유관기관 담당자

```
1. AGENT-GUIDE-001 (통합 가이드) — 지원 환경, 사전 준비
2. AGENT-WALKTHROUGH-001 (워크스루) — 단계별 설치 명령어
3. AGENT-TROUBLESHOOT-001 (트러블슈팅) — 문제 발생 시
```

### OnePass 팀 신규 개발자

```
1. AGENT-ARCH-001 (아키텍처) — 전체 구조 이해
2. AGENT-DEVREF-001 (개발 레퍼런스) — 코딩 가이드, 빌드
3. AGENT-JEUS-001 (JEUS 심층) — 한국 공공기관 WAS 깊이 이해
```

### JEUS 4/5 (JDK 1.5) 지원 관련 이해

```
1. AGENT-ARCH-001 § 4 (JDK 1.5 SSO 가능성 분석)
2. AGENT-JEUS-001 § 3 (JDK 1.5 심층 분석)
3. AGENT-DEVREF-001 § 6 (Javassist 위빙 코드 가이드)
```

---

## ✅ 핵심 Q&A

**Q: JDK 1.5 JEUS 4/5 환경에서 SSO Agent가 정말 동작하나요?**

> A: 네, 가능합니다. 단, 현재 Agent JAR 자체는 JDK 8+ 환경에서만 실행됩니다.  
> "JDK 1.5 SSO 가능"의 정확한 의미는 Javassist 위빙으로 삽입된 코드가  
> JDK 1.5 JVM의 JEUS 위에서 동작한다는 것입니다.  
> Agent 클래스 자체의 JDK 1.5 직접 실행 지원은 Sprint 21 계획입니다.  
> 상세: `AGENT-ARCH-001 § 4`, `AGENT-JEUS-001 § 3` 참조

**Q: 어떤 위빙 엔진을 사용하나요?**

> - JEUS 4/5/6: **Javassist** (JDK 1.3+ 호환)
> - JEUS 7/8 JDK 7: **Javassist** (폴백)
> - JEUS 7/8 JDK 8+: **byte-buddy**
> - JEUS 8.5/9/21: **byte-buddy**

**Q: JEUS 9 Jakarta EE는 어떻게 지원하나요?**

> `javax.servlet.Filter`와 `jakarta.servlet.Filter` 양쪽을 이중 위빙합니다.  
> 상세: `AGENT-JEUS-001 § 4.4` 참조

---

*OnePass 통합인증 플랫폼 — 행정안전부*
