# Security Policy

## 취약점 신고

Idem 의 보안 취약점을 발견하면 **공개 이슈로 올리지 말고** 아래로 보내 주십시오.

- 이메일: shipster@outlook.kr (제목에 `[SECURITY] idem` 을 붙여 주십시오)
- GitHub Private Vulnerability Reporting 이 켜져 있으면 저장소의 **Security → Report a vulnerability** 를 써도 됩니다.

받은 신고는 3 영업일 안에 접수 회신하고, 확인된 취약점은 수정 릴리스와 함께 공개합니다(원하시면 크레딧에 이름을 올립니다).

## 지원 범위

| 버전 | 지원 |
|---|---|
| `main` 최신 | ✅ |
| 1.0 동결 이후 태그 릴리스 | 최신 마이너 ✅ |
| 그 외 | ❌ |

## 설계 원칙 (참고)

- 외부 의존(Redis·DB·인가 서비스·서명키·사업자 응답) 실패는 **거부 + 감사 기록**이며, 로컬 편의용 탈출구는 운영·스테이지 프로파일에서 기동을 거부한다 — `docs/sso-im-operations-manual.md` §3.4
- 벤더 SDK·자격증명은 저장소에 두지 않는다. 시크릿이 커밋된 것을 발견하면 위 경로로 알려 주십시오.
