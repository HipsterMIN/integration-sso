#!/usr/bin/env python3
"""Keycloak 중계 로그인 끝-끝 프로브 — 시험 항목 D-19 (1.2 PR-1.2-2).

코어 로그인 프런트(Handoff 브라우저 진입)에 `provider=broker:<이름>` 으로 들어가 브라우저처럼 홉을 밟고 판정한다:
  진입 → 브로커 시작 → Keycloak 로그인 화면 → 폼 POST → gate 콜백 → hub 바인드 → continue → 기관 콜백(?ticketId=&state=)
  → 서버 간 verify(APPROVED/GUEST) → 재검증 409(1회 소비)
IT(`BrokerLoginIntegrationTest`)는 gate 를 WireMock 으로 바꿔 Idem 쪽 논리를 본다 — 이 프로브는 실제 Keycloak·gate·hub 를 지난다.

전제: hub 가 IDEM_HUB_HANDOFF_LOGIN_BROKER_PROVIDERS=<이름>(예: keycloak) 으로 떠 있다(기본은 비어 있다). DIRECT 프로파일의 서비스와
      기관 API 키가 있다 — 설치본 스모크 ⑦b 와 같은 준비:
        PUT  $HUB_URL/api/v1/admin/services/$SERVICE/profile   {"protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["$CALLBACK"]}}, …}
        POST $HUB_URL/api/v1/admin/agencies/$SERVICE/rotate-key  → newApiKey
환경변수:
  HUB_URL(기본 http://localhost:8083)  GATE_PUBLIC_URL(기본 http://localhost:8081 — 브라우저가 보는 gate 주소)
  SERVICE  AGENCY_KEY  CALLBACK(기본 https://agency.example.org/cb)  BROKER(기본 keycloak)
  시험 사용자(slo-probe.py 와 같다 — 설치 realm 에는 기본 사용자가 없다): KC_ADMIN_URL·KC_ADMIN_PASSWORD(KC_ADMIN_USER 기본 admin)만 주면
  임시 사용자를 만들어 쓰고 끝나면 지운다. 이미 있는 사용자로 돌리려면 OIDC_USER + OIDC_PASSWORD
표준 라이브러리만 쓴다(HTTP 도우미는 같은 디렉터리의 slo-probe.py). 비밀·토큰·티켓은 출력하지 않는다. 종료 코드 0 = 전 단계 통과.

알아 둘 것(2026-11-12, docs/analysis/keycloak-26-assessment.md §8.6 #7): 인가 URL 은 gate 가 Keycloak **내부 주소**(KEYCLOAK_URL)로 만든다 —
로그인 화면의 호스트가 GATE_PUBLIC_URL 과 다르면 [INFO] 로 알린다. 그 주소가 브라우저에서 닿지 않는 배치(설치본 compose·K8s)에서는 이 경로가 끝까지 가지 못한다 —
프로브는 닿지 않는 홉을 `ERR <주소>` 로 적고 "Keycloak 로그인 화면" 단계를 실패로 판정한다(2026-11-14 compose 컨테이너에서 확인 — 진입·브로커 시작까지 통과, 세 번째 홉 `http://keycloak:8080/…` 에서 실패).
"""
import importlib.util
import json
import os
import re
import sys
import urllib.error
import urllib.parse

_spec = importlib.util.spec_from_file_location("slo_probe", os.path.join(os.path.dirname(os.path.abspath(__file__)), "slo-probe.py"))
slo = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(slo)

HUB = os.environ.get("HUB_URL", "http://localhost:8083").rstrip("/")
GATE_PUBLIC = os.environ.get("GATE_PUBLIC_URL", "http://localhost:8081").rstrip("/")
SERVICE = os.environ.get("SERVICE", "")
AGENCY_KEY = os.environ.get("AGENCY_KEY", "")
CALLBACK = os.environ.get("CALLBACK", "https://agency.example.org/cb")
BROKER = os.environ.get("BROKER", "keycloak")
STATE = "broker-probe-state"


def hostpath(url: str) -> str:
    u = urllib.parse.urlparse(url)
    return f"{u.scheme}://{u.netloc}{u.path}"


def main() -> int:
    if not (SERVICE and AGENCY_KEY):
        print("SERVICE / AGENCY_KEY 가 필요합니다", file=sys.stderr)
        return 2
    cleanup = slo.ensure_user()
    if cleanup is None:
        return 2
    try:
        return run()
    finally:
        cleanup()


def run() -> int:
    print(f"브로커 로그인 프로브: hub={HUB} gate(공개)={GATE_PUBLIC} service={SERVICE} provider=broker:{BROKER} user={slo.USER}")
    q = urllib.parse.urlencode({"service": SERVICE, "callback": CALLBACK, "provider": f"broker:{BROKER}", "state": STATE})
    loc = f"{HUB}/api/v1/handoff/login?{q}"
    hops: list[str] = []
    form_seen = False
    login_host = ""
    reached = False
    unreachable = ""
    for _ in range(16):
        try:
            st, hdr, body = slo.call("GET", loc)
        except (urllib.error.URLError, OSError) as e:
            # 브라우저가 닿을 수 없는 주소로 보내졌다 — 설치본 배치(compose·K8s)에서 인가 URL 이 Keycloak 내부 주소일 때 여기서 끊긴다
            unreachable = hostpath(loc)
            hops.append(f"ERR {unreachable} ({getattr(e, 'reason', e)})")
            break
        hops.append(f"{st} {hostpath(loc)}")
        if st in (301, 302, 303, 307):
            nxt = urllib.parse.urljoin(loc, hdr.get("Location", ""))
            if nxt.startswith(CALLBACK):
                loc, reached = nxt, True
                break
            loc = nxt
            continue
        if st == 200 and not form_seen:
            m = re.search(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"', body.decode("utf-8", "replace"))
            if not m:
                break
            form_seen, login_host = True, hostpath(loc)
            action = m.group(1).replace("&amp;", "&")
            st2, hdr2, _ = slo.call("POST", action, slo.form({"username": slo.USER, "password": slo.PASSWORD, "credentialId": ""}),
                                    {"Content-Type": "application/x-www-form-urlencoded"})
            hops.append(f"{st2} POST {hostpath(action)}")
            if st2 not in (302, 303):
                break
            loc = urllib.parse.urljoin(action, hdr2.get("Location", ""))
            if loc.startswith(CALLBACK):
                reached = True
                break
            continue
        break
    for h in hops:
        print(f"    hop: {h}")
    slo.step("진입 → 브로커 시작: 302 둘(hub 로그인 프런트 → /api/v1/broker/…/authorize)",
             len(hops) >= 2 and hops[0].startswith("302") and hops[1].startswith("302") and "/api/v1/broker/" in hops[1])
    slo.step("Keycloak 로그인 화면(200, 폼)", form_seen,
             login_host or (f"닿지 않는 주소로 보내졌다: {unreachable} — 인가 URL 이 Keycloak 내부 주소다(docs/analysis/keycloak-26-assessment.md §8.6 #7)" if unreachable else "-"))
    if form_seen and not login_host.startswith(GATE_PUBLIC):
        print(f"  [INFO] 로그인 화면의 주소가 gate 공개 URL({GATE_PUBLIC})이 아니다 — 브라우저가 Keycloak 내부 주소로 보내졌다. "
              f"그 주소가 브라우저에서 닿지 않는 배치에서는 여기서 끊긴다(판정에는 넣지 않는다)")
    slo.step("로그인 → gate 콜백 → hub 바인드 → continue → 기관 콜백", reached, f"hops={len(hops)}")
    cq = urllib.parse.parse_qs(urllib.parse.urlparse(loc).query) if reached else {}
    ticket = (cq.get("ticketId") or [""])[0]
    slo.step("콜백에 ticketId·state 되돌림", bool(ticket) and cq.get("state") == [STATE], f"ticketId={'yes' if ticket else 'no'} state_match={cq.get('state') == [STATE]}")
    slo.step("FE 세션 쿠키(feSessionId)", "feSessionId" in slo.cookies)
    if not ticket:
        return report()

    def verify():
        return slo.call("POST", f"{HUB}/api/v1/handoff/verify", json.dumps({"ticketId": ticket}).encode(),
                        {"Content-Type": "application/json", "X-Agency-Code": SERVICE, "X-Agency-Key": AGENCY_KEY, "X-Correlation-Id": "broker-login-probe"},
                        use_cookies=False)

    st, _, body = verify()
    v = json.loads(body or b"{}") if body else {}
    slo.step("서버 간 verify: 200 · state APPROVED/GUEST", st == 200 and v.get("state") in ("APPROVED", "GUEST"), f"status={st} state={v.get('state', '-')}")
    st2, _, body2 = verify()
    code = (json.loads(body2 or b"{}") if body2 else {}).get("code", "-")
    slo.step("재검증: 409 E-IDO-102 (1회 소비)", st2 == 409 and code == "E-IDO-102", f"status={st2} code={code}")
    return report()


def report() -> int:
    ok = sum(1 for _, good, _ in slo.results if good)
    print(f"결과: {ok}/{len(slo.results)} 통과")
    return 0 if ok == len(slo.results) else 1


if __name__ == "__main__":
    sys.exit(main())
