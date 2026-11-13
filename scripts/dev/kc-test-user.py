#!/usr/bin/env python3
"""Keycloak realm 에 시험 사용자를 만들고 지운다 — 설치 realm 에는 기본 사용자가 없다 (1.2 PR-1.2-3a).

표준 로그인 화면(OIDC_RP)을 지나는 시험(D-8 SLO 프로브 · D-19 브로커 프로브 · k6 시나리오 12)에는 Keycloak realm 의 사용자가
있어야 한다. 1.1.x 까지는 realm import 에 든 `poc-testuser` 를 썼는데, 그 비밀번호가 저장소에 있어 설치본의 기본 계정이 됐다 —
1.2 에서 뺐다. 시험할 때 이 스크립트로 만들고 끝나면 지운다(프로브와 성능 실행기는 KC_ADMIN_* 가 있으면 알아서 그렇게 한다).

    kc-test-user.py create     사용자를 만든다. 이미 있으면 실패한다 — 남의 계정 비밀번호를 바꾸지 않는다(--reset 이면 다시 건다)
    kc-test-user.py delete     사용자를 지운다(없으면 그대로 성공)
    kc-test-user.py status     있는지 · enabled · 세션 수

환경변수:
  KC_ADMIN_URL(http://localhost:8088 — 설치본의 관리 포트)  KC_ADMIN_USER(admin)  KC_ADMIN_PASSWORD(필수)  KC_ADMIN_REALM(master)
  OIDC_REALM(idem)   OIDC_USER(idem-test-user)
  OIDC_PASSWORD        create: 이 값을 비밀번호로 건다. 없으면 무작위로 만들어 OIDC_PASSWORD_FILE 에 쓴다
  OIDC_PASSWORD_FILE   create: 만든 비밀번호를 쓸 파일(0600). OIDC_PASSWORD 가 없으면 필수

    set -a; . infra/docker/install.env; set +a
    KC_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" OIDC_PASSWORD_FILE=/tmp/idem-test-user.pw python3 -I scripts/dev/kc-test-user.py create
    OIDC_USER=idem-test-user OIDC_PASSWORD="$(cat /tmp/idem-test-user.pw)" … python3 -I scripts/dev/slo-probe.py
    KC_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" python3 -I scripts/dev/kc-test-user.py delete

표준 라이브러리만 쓴다. 비밀번호·토큰은 출력하지 않는다. 운영 realm 에 남겨 두지 않는다 — 시험이 끝나면 지운다.
"""
from __future__ import annotations
import json, os, secrets, sys, urllib.error, urllib.parse, urllib.request

DEFAULT_USER = "idem-test-user"


class KcAdminError(Exception):
    pass


class KcAdmin:
    """Keycloak 관리 REST API 가운데 시험 사용자에 필요한 것만."""

    def __init__(self, url: str, user: str, password: str, admin_realm: str = "master", realm: str = "idem"):
        self.url, self.user, self.password, self.admin_realm, self.realm = url.rstrip("/"), user, password, admin_realm, realm
        self._token: str | None = None

    def _http(self, method: str, url: str, data: bytes | None, headers: dict) -> tuple[int, dict, bytes]:
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()
        except urllib.error.URLError as e:
            raise KcAdminError(f"Keycloak 에 닿지 않는다: {self.url} ({e.reason})") from None

    def token(self) -> str:
        if self._token is None:
            st, _, body = self._http("POST", f"{self.url}/realms/{self.admin_realm}/protocol/openid-connect/token",
                                     urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli",
                                                             "username": self.user, "password": self.password}).encode(),
                                     {"Content-Type": "application/x-www-form-urlencoded"})
            if st != 200:
                raise KcAdminError(f"Keycloak 관리자 토큰 발급 실패({st}) — KC_ADMIN_USER/KC_ADMIN_PASSWORD 를 확인하세요")
            self._token = json.loads(body)["access_token"]
        return self._token

    def api(self, method: str, path: str, body: dict | None = None) -> tuple[int, dict, bytes]:
        data = json.dumps(body).encode() if body is not None else None
        for attempt in (1, 2):
            st, hdr, out = self._http(method, f"{self.url}/admin/realms/{self.realm}{path}", data,
                                      {"Authorization": f"Bearer {self.token()}", "Content-Type": "application/json"})
            if st != 401 or attempt == 2:
                break
            self._token = None  # 관리자 토큰은 수명이 짧다(기본 1분) — 시험이 길어져 만료됐으면 한 번 다시 받는다(임시 사용자 뒤처리가 여기에 걸린다)
        return st, hdr, out

    def find_user(self, username: str) -> dict | None:
        st, _, body = self.api("GET", f"/users?username={urllib.parse.quote(username)}&exact=true")
        if st != 200:
            raise KcAdminError(f"사용자 조회 실패({st}) — realm '{self.realm}' 이 있는지, 계정에 사용자 관리 권한이 있는지 확인하세요")
        users = json.loads(body)
        return users[0] if users else None

    def create_user(self, username: str, password: str) -> str:
        """사용자를 만들고 id 를 돌려준다. 프로필 필수 항목(이메일·이름)을 채워 로그인 중 '계정 정보 갱신' 화면이 끼지 않게 한다."""
        rep = {"username": username, "enabled": True, "emailVerified": True, "email": f"{username}@example.org",
               "firstName": "Idem", "lastName": "시험", "requiredActions": [],
               "credentials": [{"type": "password", "value": password, "temporary": False}]}
        st, hdr, body = self.api("POST", "/users", rep)
        if st == 409:
            raise KcAdminError(f"사용자 '{username}' 이(가) 이미 있다")
        if st != 201:
            raise KcAdminError(f"사용자 생성 실패({st}): {body.decode('utf-8', 'replace')[:200]}")
        loc = next((v for k, v in hdr.items() if k.lower() == "location"), "")
        return loc.rstrip("/").rsplit("/", 1)[-1] or (self.find_user(username) or {}).get("id", "")

    def reset_password(self, uid: str, password: str) -> None:
        st, _, body = self.api("PUT", f"/users/{uid}/reset-password", {"type": "password", "value": password, "temporary": False})
        if st != 204:
            raise KcAdminError(f"비밀번호 설정 실패({st}): {body.decode('utf-8', 'replace')[:200]}")

    def delete_user(self, uid: str) -> None:
        st, _, body = self.api("DELETE", f"/users/{uid}")
        if st not in (204, 404):
            raise KcAdminError(f"사용자 삭제 실패({st}): {body.decode('utf-8', 'replace')[:200]}")

    def sessions(self, uid: str) -> list:
        st, _, body = self.api("GET", f"/users/{uid}/sessions")
        return json.loads(body) if st == 200 else []


def random_password() -> str:
    """흔한 비밀번호 정책(길이·대소문자·숫자·특수문자)을 넘기는 무작위 값."""
    return "Tk-" + secrets.token_urlsafe(18) + "-9a"


def from_env() -> KcAdmin:
    pw = os.environ.get("KC_ADMIN_PASSWORD", "")
    if not pw:
        raise KcAdminError("KC_ADMIN_PASSWORD 를 설정하세요 (설치본의 KEYCLOAK_ADMIN_PASSWORD)")
    return KcAdmin(os.environ.get("KC_ADMIN_URL") or "http://localhost:8088", os.environ.get("KC_ADMIN_USER") or "admin", pw,
                   os.environ.get("KC_ADMIN_REALM") or "master", os.environ.get("OIDC_REALM") or "idem")


def main(argv: list[str]) -> int:
    if len(argv) < 2 or argv[1] not in ("create", "delete", "status"):
        print(__doc__, file=sys.stderr)
        return 2
    cmd, reset = argv[1], "--reset" in argv[2:]
    username = os.environ.get("OIDC_USER") or DEFAULT_USER
    try:
        kc = from_env()
        found = kc.find_user(username)
        if cmd == "status":
            if not found:
                print(f"  · 사용자 '{username}' 없음 (realm {kc.realm})")
            else:
                print(f"  · 사용자 '{username}' 있음 (realm {kc.realm}) enabled={found.get('enabled')} sessions={len(kc.sessions(found['id']))}")
            return 0
        if cmd == "delete":
            if not found:
                print(f"  ✅ 사용자 '{username}' 없음 — 할 일 없음")
            else:
                kc.delete_user(found["id"])
                print(f"  ✅ 사용자 '{username}' 을(를) 지웠다 (realm {kc.realm})")
            return 0
        password, pw_file = os.environ.get("OIDC_PASSWORD", ""), os.environ.get("OIDC_PASSWORD_FILE", "")
        if not password and not pw_file:
            raise KcAdminError("OIDC_PASSWORD(쓸 비밀번호) 또는 OIDC_PASSWORD_FILE(만든 비밀번호를 쓸 파일)이 필요합니다")
        generated = not password
        password = password or random_password()
        if found and not reset:
            raise KcAdminError(f"사용자 '{username}' 이(가) 이미 있다 — 다른 이름(OIDC_USER)을 쓰거나, 이 계정의 비밀번호를 다시 걸려면 --reset")
        if found:
            kc.reset_password(found["id"], password)
        else:
            kc.create_user(username, password)
        if generated:
            fd = os.open(pw_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(password)
            os.chmod(pw_file, 0o600)
        where = f" 비밀번호는 {pw_file} (0600)" if generated else ""
        print(f"  ✅ 시험 사용자 '{username}' — {'비밀번호를 다시 걸었다' if found else '만들었다'} (realm {kc.realm}).{where} 시험이 끝나면 delete 로 지운다")
        return 0
    except KcAdminError as e:
        print(f"  ❌ {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
