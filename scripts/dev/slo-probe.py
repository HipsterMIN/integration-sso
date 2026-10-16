#!/usr/bin/env python3
"""표준 OIDC(OIDC_RP) 기관의 RP-Initiated Logout(시험 항목 D-8 "SLO") 을 브라우저 없이 끝까지 밟고 결과를 판정한다.

    ① 이 스크립트가 기관 RP 의 Back-Channel Logout 수신기(BC_LISTEN)를 띄운다 — 프로파일의 backchannelLogoutUri 가 여기로 와야 한다
    ② RP1: gate 프런트 authorize(PKCE S256) → Keycloak 로그인 화면 → 폼 POST → redirect_uri?code=      (k6 시나리오 12 와 같은 흐름)
    ③ RP1: 토큰 교환(gate, hub 판정 포함) → id_token 의 sid·sub, userinfo 200
    ③′ RP2(OIDC_CLIENT_ID_2 가 있으면): 같은 브라우저(쿠키)로 authorize → 로그인 화면 없이 code → 토큰 → 같은 sid (SSO)
    ④ (KC_ADMIN_* 가 있으면) Keycloak 관리 API 로 사용자 세션이 있는지
    ⑤ RP1 이 end_session_endpoint(gate) 에 id_token_hint 로 로그아웃 → gate 가 Idem 쪽을 먼저 정리하고 Keycloak 에 넘긴다
    ⑥ RP2 의 수신기(BC_PATH_2)에 logout_token 이 오는지(BC_WAIT_SECONDS 안) → events 에 backchannel-logout, aud=RP2, sid 가 같은지.
       판정은 RP2 로 한다(SLO 의 의미 — 다른 RP 의 세션이 끝나는가). Keycloak 24 는 로그아웃을 시작한 RP1 에도 보내는데 참고로만 적는다.
       RP2 가 없으면 이 단계는 건너뛴다.
    ※ 프록시가 있는 환경(샌드박스·CI 러너)에서는 Keycloak 의 아웃바운드가 HTTPS_PROXY 로 가서 수신기에 닿지 않는다 — Keycloak 을
       NO_PROXY 에 RP 호스트를 넣어(또는 프록시 없이) 띄운다. 프로파일 검증은 루프백·사설 주소를 거부하므로 수신기 호스트는 공개 이름이어야
       한다(샌드박스는 /etc/hosts 로 127.0.0.1 에 맵핑).
    ⑦ (KC_ADMIN_*) 세션이 사라졌는지, 옛 access_token(RP1·RP2) 으로 userinfo 가 401 인지

환경변수: GATE_URL(http://localhost:8081) GATE_PUBLIC_URL(폼 action 이 공개 주소면 바꿔 친다) OIDC_REALM(idem)
          OIDC_CLIENT_ID OIDC_CLIENT_SECRET(필수) OIDC_REDIRECT_URI(프로파일 redirectUris 중 하나) OIDC_USER OIDC_PASSWORD(필수)
          OIDC_CLIENT_ID_2 OIDC_CLIENT_SECRET_2 OIDC_REDIRECT_URI_2(기본 OIDC_REDIRECT_URI) — 두 번째 RP(Back-Channel 수신 확인용)
          BC_LISTEN(0.0.0.0:9099) BC_PATH(/bc, RP1) BC_PATH_2(/bc2, RP2) BC_WAIT_SECONDS(15)
          KC_ADMIN_URL(예 http://localhost:8088, 선택) KC_ADMIN_USER KC_ADMIN_PASSWORD KC_ADMIN_REALM(master)
표준 라이브러리만 쓴다. 비밀·토큰은 출력하지 않는다. 모든 필수 단계가 통과하면 종료 코드 0.
"""
from __future__ import annotations
import base64, hashlib, html, http.server, json, os, re, secrets, sys, threading, time, urllib.error, urllib.parse, urllib.request

GATE = os.environ.get("GATE_URL", "http://localhost:8081").rstrip("/")
GATE_PUBLIC = os.environ.get("GATE_PUBLIC_URL", GATE).rstrip("/")
REALM = os.environ.get("OIDC_REALM", "idem")
CLIENT_ID = os.environ.get("OIDC_CLIENT_ID", "idem-svc-SLO_RP")
CLIENT_SECRET = os.environ.get("OIDC_CLIENT_SECRET", "")
REDIRECT_URI = os.environ.get("OIDC_REDIRECT_URI", "https://rp.example.org/callback")
CLIENT_ID_2 = os.environ.get("OIDC_CLIENT_ID_2", "")
CLIENT_SECRET_2 = os.environ.get("OIDC_CLIENT_SECRET_2", "")
REDIRECT_URI_2 = os.environ.get("OIDC_REDIRECT_URI_2", REDIRECT_URI)
USER = os.environ.get("OIDC_USER", "poc-testuser")
PASSWORD = os.environ.get("OIDC_PASSWORD", "")
BC_LISTEN = os.environ.get("BC_LISTEN", "0.0.0.0:9099")
BC_PATH = os.environ.get("BC_PATH", "/bc")
BC_PATH_2 = os.environ.get("BC_PATH_2", "/bc2")
BC_WAIT = float(os.environ.get("BC_WAIT_SECONDS", "15"))
KC_ADMIN_URL = os.environ.get("KC_ADMIN_URL", "").rstrip("/")
KC_ADMIN_USER = os.environ.get("KC_ADMIN_USER", "")
KC_ADMIN_PASSWORD = os.environ.get("KC_ADMIN_PASSWORD", "")
KC_ADMIN_REALM = os.environ.get("KC_ADMIN_REALM", "master")
OIDC = f"{GATE}/realms/{REALM}/protocol/openid-connect"

results: list[tuple[str, bool, str]] = []


def step(name: str, ok: bool, detail: str = "") -> bool:
    results.append((name, ok, detail))
    print(f"  [{'OK' if ok else 'FAIL'}] {name}{(' — ' + detail) if detail else ''}")
    return ok


# ── Back-Channel Logout 수신기 (기관 RP 역할) ──────────────────────────────────
received: list[dict] = []
received_cond = threading.Condition()


def wait_for(path: str, seconds: float) -> dict | None:
    """path 로 온 첫 POST 를 seconds 안에 기다린다"""
    deadline = time.monotonic() + seconds
    with received_cond:
        while True:
            for r in received:
                if r["path"] == path:
                    return r
            left = deadline - time.monotonic()
            if left <= 0:
                return None
            received_cond.wait(left)


class Receiver(http.server.BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "replace")
        form = urllib.parse.parse_qs(body)
        tok = (form.get("logout_token") or [""])[0]
        received.append({"path": self.path.split("?")[0], "token": tok, "content_type": self.headers.get("Content-Type", "")})
        with received_cond:
            received_cond.notify_all()
        self.send_response(200)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def log_message(self, *_):  # 조용히
        pass


def start_receiver():
    host, port = BC_LISTEN.rsplit(":", 1)
    srv = http.server.ThreadingHTTPServer((host, int(port)), Receiver)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# ── HTTP 도우미 (리다이렉트 안 따라감, 쿠키는 단순 병합) ──────────────────────────
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_):
        return None


opener = urllib.request.build_opener(NoRedirect)
cookies: dict[str, str] = {}


def remember(headers):
    for v in headers.get_all("Set-Cookie") or []:
        nv = v.split(";", 1)[0]
        if "=" in nv:
            n, val = nv.split("=", 1)
            if val:
                cookies[n.strip()] = val
            else:
                cookies.pop(n.strip(), None)


def call(method: str, url: str, data: bytes | None = None, headers: dict | None = None, use_cookies=True):
    h = dict(headers or {})
    if use_cookies and cookies:
        h["Cookie"] = "; ".join(f"{k}={v}" for k, v in cookies.items())
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with opener.open(req, timeout=20) as r:
            remember(r.headers)
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        remember(e.headers)
        return e.code, dict(e.headers), e.read()


def form(d: dict) -> bytes:
    return urllib.parse.urlencode(d).encode()


def jwt_payload(tok: str) -> dict:
    try:
        p = tok.split(".")[1]
        return json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))
    except Exception:
        return {}


def rebase(url: str) -> str:
    if GATE_PUBLIC != GATE and url.startswith(GATE_PUBLIC):
        return GATE + url[len(GATE_PUBLIC):]
    return url


# ── Keycloak 관리 API (선택) ─────────────────────────────────────────────────────
def kc_admin_token() -> str | None:
    if not (KC_ADMIN_URL and KC_ADMIN_USER and KC_ADMIN_PASSWORD):
        return None
    st, _, body = call("POST", f"{KC_ADMIN_URL}/realms/{KC_ADMIN_REALM}/protocol/openid-connect/token",
                       form({"grant_type": "password", "client_id": "admin-cli", "username": KC_ADMIN_USER, "password": KC_ADMIN_PASSWORD}),
                       {"Content-Type": "application/x-www-form-urlencoded"}, use_cookies=False)
    return json.loads(body).get("access_token") if st == 200 else None


def kc_sessions(admin_tok: str) -> list[dict] | None:
    h = {"Authorization": f"Bearer {admin_tok}"}
    st, _, body = call("GET", f"{KC_ADMIN_URL}/admin/realms/{REALM}/users?username={urllib.parse.quote(USER)}&exact=true", headers=h, use_cookies=False)
    if st != 200:
        return None
    users = json.loads(body)
    if not users:
        return None
    uid = users[0]["id"]
    st, _, body = call("GET", f"{KC_ADMIN_URL}/admin/realms/{REALM}/users/{uid}/sessions", headers=h, use_cookies=False)
    return json.loads(body) if st == 200 else None


def authorize(client_id: str, redirect_uri: str):
    """authorize 요청 — 로그인 화면(200, 폼 action) 또는 SSO 로 바로 302(code). (status, action, code, state, verifier)"""
    verifier = secrets.token_hex(32)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state, nonce = secrets.token_hex(8), secrets.token_hex(8)
    q = urllib.parse.urlencode({"client_id": client_id, "response_type": "code", "scope": "openid", "redirect_uri": redirect_uri,
                                "state": state, "nonce": nonce, "code_challenge": challenge, "code_challenge_method": "S256"})
    st, hdr, body = call("GET", f"{OIDC}/auth?{q}")
    m = re.search(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"', body.decode("utf-8", "replace")) if st == 200 else None
    code = ""
    if st == 302:
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(hdr.get("Location", "")).query)
        if (qs.get("state") or [""])[0] == state and hdr.get("Location", "").startswith(redirect_uri):
            code = (qs.get("code") or [""])[0]
    return st, (html.unescape(m.group(1)) if m else None), code, state, verifier


def exchange(client_id: str, client_secret: str, redirect_uri: str, code: str, verifier: str):
    st, _, body = call("POST", f"{OIDC}/token", form({"grant_type": "authorization_code", "code": code, "redirect_uri": redirect_uri,
                                                       "client_id": client_id, "client_secret": client_secret, "code_verifier": verifier}),
                       {"Content-Type": "application/x-www-form-urlencoded"}, use_cookies=False)
    tok = json.loads(body or b"{}") if body else {}
    return st, tok, jwt_payload(tok.get("id_token", ""))


def userinfo(access_token: str):
    st, _, body = call("GET", f"{OIDC}/userinfo", headers={"Authorization": f"Bearer {access_token}"}, use_cookies=False)
    return st, (json.loads(body or b"{}") if st == 200 else {})


def main() -> int:
    if not CLIENT_SECRET or not PASSWORD:
        print("OIDC_CLIENT_SECRET / OIDC_PASSWORD 가 필요합니다", file=sys.stderr)
        return 2
    two = bool(CLIENT_ID_2 and CLIENT_SECRET_2)
    print(f"SLO 프로브: gate={GATE} realm={REALM} rp1={CLIENT_ID} rp2={CLIENT_ID_2 or '-'} user={USER} receiver={BC_LISTEN}{BC_PATH}{(',' + BC_PATH_2) if two else ''}")
    srv = start_receiver()
    try:
        # ② RP1 authorize → 로그인 화면 → 폼 POST → code
        st, action, _, state, verifier = authorize(CLIENT_ID, REDIRECT_URI)
        if not step("rp1 authorize: Keycloak 로그인 화면(200, 폼)", st == 200 and bool(action), f"status={st}"):
            return 1
        st, hdr, _ = call("POST", rebase(action), form({"username": USER, "password": PASSWORD, "credentialId": ""}),
                          {"Content-Type": "application/x-www-form-urlencoded"})
        loc = hdr.get("Location", "")
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(loc).query)
        code = (qs.get("code") or [""])[0]
        if not step("rp1 login: 302 → redirect_uri?code=&state=", st == 302 and loc.startswith(REDIRECT_URI) and bool(code) and (qs.get("state") or [""])[0] == state,
                    f"status={st} location_host={urllib.parse.urlparse(loc).netloc or '-'}"):
            return 1
        # ③ RP1 토큰 교환 (gate — hub 판정 포함)
        st, tok, id_claims = exchange(CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, code, verifier)
        sid, sub = id_claims.get("sid"), id_claims.get("sub")
        if not step("rp1 token: 200 + id_token(sid·sub)", st == 200 and bool(tok.get("access_token")) and bool(sid) and bool(sub),
                    f"status={st} error={tok.get('error', '-')} sid={'yes' if sid else 'no'}"):
            return 1
        st, ui = userinfo(tok["access_token"])
        step("rp1 userinfo: 200 (idem_state)", st == 200, f"status={st} idem_state={ui.get('idem_state', '-')}")
        # ③′ RP2 — 같은 브라우저 쿠키로 SSO
        tok2 = {}
        if two:
            st, action2, code2, _, verifier2 = authorize(CLIENT_ID_2, REDIRECT_URI_2)
            if not step("rp2 authorize: SSO 로 로그인 화면 없이 302 code", st == 302 and bool(code2), f"status={st} login_form={'yes' if action2 else 'no'}"):
                return 1
            st, tok2, id2 = exchange(CLIENT_ID_2, CLIENT_SECRET_2, REDIRECT_URI_2, code2, verifier2)
            step("rp2 token: 200 + 같은 sid (한 Keycloak 세션에 RP 둘)", st == 200 and bool(tok2.get("access_token")) and id2.get("sid") == sid,
                 f"status={st} error={tok2.get('error', '-')} sid_match={id2.get('sid') == sid}")
        else:
            print("  [SKIP] rp2 (OIDC_CLIENT_ID_2/SECRET_2 없음) — Back-Channel 수신은 두 번째 RP 로만 확인할 수 있다")
        # ④ Keycloak 세션 확인(선택)
        admin_tok = kc_admin_token()
        before = kc_sessions(admin_tok) if admin_tok else None
        if admin_tok is not None:
            step("keycloak: 로그인 뒤 사용자 세션 있음", before is not None and len(before) >= 1, f"sessions={len(before) if before is not None else '?'}")
        else:
            print("  [SKIP] keycloak 관리 API 확인 (KC_ADMIN_* 없음)")
        # ⑤ RP1 의 RP-Initiated Logout (gate end_session_endpoint)
        received.clear()
        st, hdr, body = call("POST", f"{OIDC}/logout", form({"id_token_hint": tok["id_token"], "client_id": CLIENT_ID}),
                             {"Content-Type": "application/x-www-form-urlencoded"}, use_cookies=False)
        step("rp1 logout: end_session_endpoint 응답(200/204/302)", st in (200, 204, 302), f"status={st}")
        # ⑥ Back-Channel Logout 수신 — 같은 세션의 다른 RP(RP2) 가 받는다
        if two:
            r2 = wait_for(BC_PATH_2, BC_WAIT)
            lt = jwt_payload(r2["token"]) if r2 else {}
            events = lt.get("events") or {}
            aud = lt.get("aud")
            aud_ok = (aud == CLIENT_ID_2) or (isinstance(aud, list) and CLIENT_ID_2 in aud)
            step("backchannel: rp2 수신기에 logout_token 도착", r2 is not None and bool(r2["token"]), f"path={BC_PATH_2} within={BC_WAIT:.0f}s")
            step("backchannel: logout_token 의 events·aud(rp2)·sid", "http://schemas.openid.net/event/backchannel-logout" in events and aud_ok and lt.get("sid") == sid,
                 f"iss={lt.get('iss', '-')} aud_ok={aud_ok} sid_match={lt.get('sid') == sid} sub_match={lt.get('sub') == sub}")
            r1 = wait_for(BC_PATH, 3.0)
            print(f"  [INFO] rp1 수신기({BC_PATH}): {'도착' if r1 else '없음'} — Keycloak 24 는 로그아웃을 시작한 RP 에도 logout_token 을 보낸다(판정에는 넣지 않는다)")
        # ⑦ 뒤처리 확인
        if admin_tok is not None:
            time.sleep(1.0)
            after = kc_sessions(admin_tok)
            step("keycloak: 로그아웃 뒤 사용자 세션 없음", after is not None and len(after) == 0, f"sessions={len(after) if after is not None else '?'}")
        st, _ = userinfo(tok["access_token"])
        step("rp1 userinfo: 옛 access_token 은 401", st == 401, f"status={st}")
        if two and tok2.get("access_token"):
            st, _ = userinfo(tok2["access_token"])
            step("rp2 userinfo: 옛 access_token 은 401 (SLO)", st == 401, f"status={st}")
    finally:
        srv.shutdown()
    failed = [n for n, ok, _ in results if not ok]
    print(f"결과: {len(results) - len(failed)}/{len(results)} 통과" + (f" — 실패: {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
