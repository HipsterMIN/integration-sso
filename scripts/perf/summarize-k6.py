#!/usr/bin/env python3
"""k6 --summary-export JSON 묶음 → 마크다운 표 (scripts/perf/run-k6.sh 의 ⑦ 단계, 단독 실행도 된다).

    python3 scripts/perf/summarize-k6.py k6/reports/20261015-0200 [--label "GS 시험 환경"]

읽는 것: <dir>/*.summary.json (k6 의 end-of-test 요약 — Trend 는 avg·p(95)·p(99)…, Rate 는 passes/fails/value, Counter 는 count,
         문턱은 metrics.<이름>.thresholds — k6 1.x 의 이 형식에서는 값 true 가 '문턱을 넘었다(실패)' 다)
쓰는 것: 표준 출력(마크다운). 시나리오마다 요청 수·실패율·체크·사용자 정의 지표(…_duration·…_rate_limited·…_denied) 의 p95/p99 와 문턱 판정.
"""
import argparse
import glob
import json
import os
import sys

SCENARIO_TITLE = {
    "10-handoff-login":     "10 Handoff 브라우저 로그인 끝-끝 (MOCK)",
    "11-handoff-issue-api": "11 Handoff 발급·검증 API + 200 tps 한도",
    "12-oidc-token":        "12 표준 OIDC 토큰 교환 (gate+Keycloak+hub 판정)",
}

# 시나리오별로 표에 올릴 지표(순서대로) — 없는 것은 건너뛴다
INTERESTING = {
    "10-handoff-login": [
        "handoff_login_entry_duration", "handoff_login_continue_duration", "handoff_login_e2e_duration", "handoff_verify_duration",
    ],
    "11-handoff-issue-api": [
        "handoff_issue_duration{phase:sustain}", "handoff_verify_duration{phase:sustain}",
        "handoff_issue_duration{phase:limit}", "handoff_issue_duration", "handoff_verify_duration",
        "handoff_issue_rate_limited{phase:sustain}", "handoff_issue_rate_limited{phase:limit}", "handoff_issue_rate_limited{phase:recovery}",
        "handoff_rate_limited_total",
    ],
    "12-oidc-token": [
        "oidc_authorize_duration", "oidc_login_duration", "oidc_token_duration", "oidc_userinfo_duration", "oidc_e2e_duration",
        "oidc_token_denied_total",
    ],
}


def ms(v):
    return "-" if v is None else f"{v:,.1f}"


def pct(v):
    return "-" if v is None else f"{v * 100:.2f}%"


def threshold_marks(m):
    th = m.get("thresholds") or {}
    if not th:
        return ""
    # true = crossed(실패)
    return " ".join(("✗ " if crossed else "✓ ") + expr for expr, crossed in th.items())


def metric_row(name, m):
    t = m.get("type") or ("trend" if "p(95)" in m else "rate" if "value" in m and "passes" in m else "counter" if "count" in m else "?")
    if "p(95)" in m:
        val = f"avg {ms(m.get('avg'))} · p95 **{ms(m.get('p(95)'))}** · p99 {ms(m.get('p(99)'))} · max {ms(m.get('max'))} ms"
    elif "passes" in m and "value" in m:
        val = f"{pct(m.get('value'))} ({m.get('passes', 0):,} / {m.get('passes', 0) + m.get('fails', 0):,})"
    elif "count" in m:
        val = f"{m.get('count', 0):,} 건"
    else:
        val = json.dumps(m, ensure_ascii=False)[:80]
    return f"| `{name}` | {val} | {threshold_marks(m)} |"


def summarize(path, label):
    data = json.load(open(path, encoding="utf-8"))
    metrics = data.get("metrics", {})
    name = os.path.basename(path).replace(".summary.json", "")
    title = SCENARIO_TITLE.get(name, name)
    reqs = metrics.get("http_reqs", {})
    failed = metrics.get("http_req_failed", {})
    checks = metrics.get("checks", {})
    iters = metrics.get("iterations", {})
    dur = metrics.get("http_req_duration", {})
    crossed = [f"{k}: {expr}" for k, m in metrics.items() for expr, c in (m.get("thresholds") or {}).items() if c]
    verdict = "✅ PASS" if not crossed else "❌ FAIL (" + "; ".join(crossed) + ")"

    out = [f"### {title}", ""]
    out.append(f"- 환경: {label} · 판정: **{verdict}**")
    out.append(f"- HTTP 요청 {reqs.get('count', 0):,} 건 ({reqs.get('rate', 0):.1f} req/s), 반복 {iters.get('count', 0):,}, "
               f"실패율 {pct(failed.get('value'))}, 체크 {pct(checks.get('value'))} ({checks.get('passes', 0):,}/{checks.get('passes', 0) + checks.get('fails', 0):,}), "
               f"전체 http_req_duration p95 {ms(dur.get('p(95)'))} ms")
    out.append("")
    out.append("| 지표 | 값 | 문턱 |")
    out.append("|---|---|---|")
    listed = set()
    for key in INTERESTING.get(name, []):
        if key in metrics:
            out.append(metric_row(key, metrics[key]))
            listed.add(key)
    # 표에 안 올린 사용자 정의 지표(문턱이 있는 것)도 보인다
    for key, m in sorted(metrics.items()):
        if key in listed or key.startswith(("http_", "iteration", "vus", "data_", "checks", "iterations", "dropped")):
            continue
        if m.get("thresholds") or key.endswith(("_duration", "_total")):
            out.append(metric_row(key, m))
    out.append("")
    return "\n".join(out), verdict.startswith("✅")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    files = sorted(glob.glob(os.path.join(a.dir, "*.summary.json")))
    if not files:
        print(f"요약 JSON 이 없다: {a.dir}", file=sys.stderr)
        return 1
    env = os.path.join(a.dir, "environment.txt")
    print(f"## k6 성능 측정 요약 — {os.path.basename(os.path.abspath(a.dir))}")
    print()
    if os.path.exists(env):
        print("```")
        print(open(env, encoding="utf-8").read().rstrip())
        print("```")
        print()
    all_ok = True
    for f in files:
        text, ok = summarize(f, a.label)
        all_ok = all_ok and ok
        print(text)
    print("종합: " + ("✅ 전 시나리오 문턱 통과" if all_ok else "❌ 문턱 미달 시나리오 있음"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
