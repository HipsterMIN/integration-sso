#!/usr/bin/env python3
"""docs-lint — 문서 정합성 검사 (NamingGuard 의 문서판, 플랜 §7).

사람이 거듭 놓친 불일치를 CI 에서 막는다. 규칙마다 위반은 `파일:줄: 메시지` 로 출력하고, 하나라도 있으면 exit 1.
  V  버전 — 루트 build.gradle.kts 의 version 이 단일 출처. Helm Chart·콘솔 package·SDK README·웹훅 platformVersion 기본값·
     현재 버전 문구(CLAUDE/AGENTS/README)·제품 설명서·GS 착수·설치 매뉴얼 tar 이름·연동 가이드 예시가 같은 값이어야 한다.
  T  시험 항목표 — 표의 행 수 = 제목의 "= N항목" = 집계 합계 = 집계 그룹 합 = 그룹별 ID 목록 길이; 집계 합계의 자동/CI/로컬 IT/수동 수가
     docs/README·gs-kickoff 의 문구와 같다; 집계에 적힌 ID 는 표에 있어야 한다.
  E  오류 코드 — 운영 문서가 인용한 E-IDO/E-AGENCY/E-IM/E-QS/E-AUTHZ 코드는 코드(PlatformErrorCode·AuthzErrorCode·registry 핸들러)에 정의돼 있어야 한다.
  N  구 이름 — 운영 문서에 구 런타임 식별자(IDO_*·QSIGN_*·QIM_*·QAUTHZ_* 환경변수, ${ido.*} 설정 키)가 없어야 한다.
  L  링크 — 루트·docs 최상위·매뉴얼·인증 문서의 상대 링크가 실제 파일을 가리켜야 한다.
  C  CHANGELOG — 첫 버전 헤더 `## [X.Y.Z]` 가 빌드 버전과 같아야 한다.
실행: python3 scripts/ci/docs-lint.py   (저장소 루트, 의존성 없음). 줄 끝에 `docs-lint:ignore` 가 있으면 그 줄은 N·E·L 검사에서 뺀다.
"""
import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
os.chdir(ROOT)
errors: list[str] = []


def err(path: str, line: int, msg: str) -> None:
    errors.append(f"{path}:{line}: {msg}")


def read(path: str) -> str:
    with open(path, encoding='utf-8', errors='replace') as f:
        return f.read()


def lines(path: str):
    for i, l in enumerate(read(path).split('\n'), 1):
        if 'docs-lint:ignore' in l:
            continue
        yield i, l


def find_line(path: str, needle: str) -> int:
    for i, l in enumerate(read(path).split('\n'), 1):
        if needle in l:
            return i
    return 0


# ── V. 버전 ──────────────────────────────────────────────────────────────
m = re.search(r'^\s*version = "(\d+\.\d+\.\d+)"', read('build.gradle.kts'), re.M)
if not m:
    err('build.gradle.kts', 1, 'version = "X.Y.Z" 를 찾지 못함')
    print('\n'.join(errors)); sys.exit(1)
V = m.group(1)
VE = re.escape(V)
# (파일, 반드시 있어야 하는 정규식(버전 자리에 {V}), 설명)
VERSION_SITES = [
    ('infra/helm/idem/Chart.yaml', r'^version: {V}\s*$', 'Helm chart version'),
    ('infra/helm/idem/Chart.yaml', r'^appVersion: "{V}"\s*$', 'Helm appVersion'),
    ('idem-console-admin/package.json', r'"version": "{V}"', '콘솔 package.json'),
    ('idem-console-admin/package-lock.json', r'"version": "{V}"', '콘솔 package-lock.json'),
    ('idem-sdk-java/README.md', r'version:\s+{V}\b', 'SDK README 버전'),
    ('idem-sdk-java/CHANGELOG.md', r'^## \[{V}\]', 'SDK CHANGELOG 항목'),
    ('idem-hub/src/main/resources/application.yml', r'IDEM_HUB_PLATFORM_VERSION:{V}\}', '웹훅 platform-version 기본값'),
    ('idem-hub/src/main/java/io/github/hipstermin/idem/hub/webhook/WebhookDispatcherService.java', r'platform-version:{V}\}', '@Value 기본값'),
    ('idem-hub/src/main/java/io/github/hipstermin/idem/hub/webhook/WebhookDispatchOutboxRelay.java', r'platform-version:{V}\}', '@Value 기본값'),
    ('CLAUDE.md', r'\*\*현재 버전\*\*: \*\*v{V}\*\*', 'CLAUDE.md 현재 버전'),
    ('AGENTS.md', r'\*\*현재 버전\*\*: v{V}\b', 'AGENTS.md 현재 버전'),
    ('README.md', r'\*\*현재 버전\*\*: \*\*v{V}\*\*', 'README 현재 버전'),
    ('docs/manuals/product-spec.md', r'\| 제품명 / 버전 \| Idem {V} \(태그 `v{V}`', '제품 설명서 버전'),
    ('docs/certification/gs-kickoff.md', r'\*\*시험 대상 제품\*\*: Idem {V}\b', 'GS 착수 시험 대상'),
    ('docs/manuals/installation-manual.md', r'idem-{V}-core', '설치 매뉴얼 번들 이름'),
    ('docs/manuals/installation-manual.md', r'git checkout v{V}\b', '설치 매뉴얼 소스 태그'),
    ('docs/idem-sdk-java-usage-guide.md', r'`idem-sdk-java {V}`', 'SDK 사용 가이드 버전'),
    ('docs/sso-agency-developer-guide.md', r'X-Platform-Version: {V}\b', '개발자 가이드 웹훅 예시'),
    ('docs/sso-agency-developer-guide.md', r'idem-sdk-java:{V}`', '개발자 가이드 Maven 좌표'),
    ('docs/sso-agency-operations-guide.md', r'\| Idem 설치본 \| {V} \|', '운영 가이드 버전 표'),
    ('scripts/k8s/README.md', r'IMAGE_TAG={V}\b', 'K8s README 예시 태그'),
]
for path, pat, what in VERSION_SITES:
    if not os.path.exists(path):
        err(path, 1, f'[V] 파일 없음 ({what})'); continue
    if not re.search(pat.replace('{V}', VE), read(path), re.M):
        hint = re.search(r'\d+\.\d+\.\d+', pat.replace('{V}', '')) and '' or ''
        err(path, 1, f'[V] {what} 가 빌드 버전 {V} 와 다름 (기대 패턴: {pat.replace("{V}", V)})')

# ── C. CHANGELOG ────────────────────────────────────────────────────────
cl = read('CHANGELOG.md')
mv = re.search(r'^## \[(\d+\.\d+\.\d+)\]', cl, re.M)
if not mv:
    err('CHANGELOG.md', 1, '[C] 버전 헤더 `## [X.Y.Z]` 없음')
elif mv.group(1) != V:
    err('CHANGELOG.md', find_line('CHANGELOG.md', mv.group(0)), f'[C] 첫 버전 헤더 {mv.group(1)} ≠ 빌드 버전 {V}')

# ── T. 시험 항목표 ─────────────────────────────────────────────────────
TI = 'docs/manuals/test-items.md'
ti = read(TI)
rows = [m.group(1) for m in re.finditer(r'^\| ([A-H]-\d+) \|', ti, re.M)]
n_rows = len(rows)
if len(set(rows)) != n_rows:
    dup = sorted({r for r in rows if rows.count(r) > 1})
    err(TI, 1, f'[T] 중복 ID {dup}')
mt = re.search(r'^# .*= (\d+)항목\)', ti, re.M)
if not mt:
    err(TI, 1, '[T] 제목에 "= N항목)" 없음')
elif int(mt.group(1)) != n_rows:
    err(TI, 1, f'[T] 제목 {mt.group(1)}항목 ≠ 표 행 수 {n_rows}')
# 집계 표: | 구분 | 항목 수 | 자동 | 그중 CI | 로컬 IT | 수동 |
tally = re.search(r'^## 집계\n(.*?)(?=^## |\Z)', ti, re.M | re.S)
groups = []
total = None
if not tally:
    err(TI, 1, '[T] "## 집계" 절 없음')
else:
    for ln in tally.group(1).split('\n'):
        if not ln.startswith('|') or ln.startswith('|---') or ln.startswith('| 구분'):
            continue
        cells = [c.strip() for c in ln.strip().strip('|').split('|')]
        if len(cells) != 6:
            continue
        nums = []
        for c in cells[1:]:
            mm = re.match(r'\**(\d+)', c)
            nums.append(int(mm.group(1)) if mm else None)
        # 세는 ID 목록은 숫자 바로 뒤의 첫 괄호 안 — 그 뒤 설명("— G-3 은 …", "(G-9 의 … 수동)")은 세지 않는다
        ids = []
        for c in cells[1:]:
            mm = re.match(r'\**(\d+)\**\s*\(([^)]*)\)', c)
            # 0 뒤의 괄호는 목록이 아니라 설명("0 (G-9 의 LLM 끝-끝은 수동)")
            ids.append(re.findall(r'\b[A-H]-\d+\b', mm.group(2)) if mm and int(mm.group(1)) > 0 else [])
        if cells[0].strip('*') == '합계':
            total = nums
        else:
            groups.append((cells[0], nums, ids))
    if total is None:
        err(TI, find_line(TI, '## 집계'), '[T] 집계에 합계 행 없음')
    else:
        if total[0] != n_rows:
            err(TI, find_line(TI, '합계'), f'[T] 합계 항목 수 {total[0]} ≠ 표 행 수 {n_rows}')
        if total[1] is not None and total[4] is not None and total[0] != (total[1] or 0) + (total[4] or 0):
            err(TI, find_line(TI, '합계'), f'[T] 합계: 항목 {total[0]} ≠ 자동 {total[1]} + 수동 {total[4]}')
        if total[1] is not None and total[2] is not None and total[3] is not None and total[1] != total[2] + total[3]:
            err(TI, find_line(TI, '합계'), f'[T] 합계: 자동 {total[1]} ≠ CI {total[2]} + 로컬 IT {total[3]}')
        for col, name in ((0, '항목 수'), (1, '자동'), (2, 'CI'), (3, '로컬 IT'), (4, '수동')):
            s = sum((g[1][col] or 0) for g in groups)
            if total[col] is not None and s != total[col]:
                err(TI, find_line(TI, '합계'), f'[T] 집계 {name}: 그룹 합 {s} ≠ 합계 {total[col]}')
    for gname, nums, ids in groups:
        for col, name in ((0, '항목 수'), (2, 'CI'), (3, '로컬 IT'), (4, '수동')):
            if ids[col] and nums[col] is not None and len(ids[col]) != nums[col]:
                err(TI, find_line(TI, f'| {gname} |'), f'[T] {gname} {name}: 숫자 {nums[col]} ≠ 나열된 ID {len(ids[col])}개 {ids[col]}')
            for i in ids[col]:
                if i not in rows:
                    err(TI, find_line(TI, f'| {gname} |'), f'[T] {gname} {name}: ID {i} 가 표에 없음')
    # 바깥 문서의 수치
    if total:
        A, CI_, LIT, MAN = total[1], total[2], total[3], total[4]
        for path, pat, what in [
            ('docs/README.md', rf'시험 항목표\({n_rows}항목', 'docs/README 항목 수'),
            ('docs/certification/gs-kickoff.md', rf'초안 {n_rows}항목\(', 'gs-kickoff 항목 수'),
            ('docs/certification/gs-kickoff.md', rf'자동 {A} — CI {CI_} \+ 로컬 IT {LIT}, 수동 {MAN}\)', 'gs-kickoff 자동/수동 수'),
        ]:
            if not re.search(pat, read(path)):
                err(path, 1, f'[T] {what} 가 시험 항목표 집계(항목 {n_rows}, 자동 {A}, CI {CI_}, 로컬 IT {LIT}, 수동 {MAN})와 다름')

# ── 운영 문서 집합 (E·N·L) ─────────────────────────────────────────────
OPS_DOCS = sorted(set(
    glob.glob('docs/manuals/*.md') + glob.glob('docs/certification/*.md') + [
        'docs/install.md', 'docs/install-inputs.md', 'docs/onboarding-guide.md', 'docs/admin-auth.md',
        'docs/sso-agency-integration-guide.md', 'docs/sso-agency-developer-guide.md', 'docs/sso-agency-operations-guide.md',
        'docs/idem-sdk-java-usage-guide.md', 'docs/requirements-checklist.md', 'docs/local-dev-workflow.md', 'docs/README.md',
        'README.md', 'CLAUDE.md', 'AGENTS.md', 'CHANGELOG.md', 'idem-console-admin/README.md', 'idem-sdk-java/README.md',
        'scripts/k8s/README.md', 'infra/docker/README.md',
    ]))
OPS_DOCS = [p for p in OPS_DOCS if os.path.exists(p)]

# ── E. 오류 코드 ───────────────────────────────────────────────────────
defined: set[str] = set()
for src in ['idem-common/src/main/java', 'idem-authz/src/main/java', 'idem-registry/src/main/java', 'idem-gate/src/main/java', 'idem-hub/src/main/java']:
    for path in glob.glob(src + '/**/*.java', recursive=True):
        defined.update(re.findall(r'"(E-[A-Z]+-\d{3}(?:-[A-Z]+)?)"', read(path)))
CODE_RE = re.compile(r'\bE-(?:IDO|AGENCY|IM|QS|AUTHZ)-\d{3}(?:-[A-Z]+)?\b')
for path in OPS_DOCS:
    for i, l in lines(path):
        for c in CODE_RE.findall(l):
            if c not in defined:
                err(path, i, f'[E] 오류 코드 {c} 가 코드에 정의돼 있지 않음')

# ── N. 구 이름 ─────────────────────────────────────────────────────────
LEGACY = [
    (re.compile(r'\b(IDO|QSIGN|QIM|QAUTHZ)_[A-Z0-9_]+'), '구 환경변수 이름 (IDEM_HUB_*/IDEM_GATE_*/… 로)'),
    (re.compile(r'\$\{(ido|qsign|qim)\.[a-z]'), '구 설정 키 ${ido.*}'),
    (re.compile(r'(?<![\w./-])(ido|qsign|qim)\.(hub|gate|registry|authz|kms|audit|handoff|fe|admin|webhook|security)\b'), '구 설정 키 ido.* / qsign.* / qim.*'),
    (re.compile(r'/realms/onepass\b'), '구 realm 이름'),
]
for path in OPS_DOCS:
    for i, l in lines(path):
        for rx, what in LEGACY:
            m = rx.search(l)
            if m:
                err(path, i, f'[N] {what}: {m.group(0)}')

# ── L. 상대 링크 ───────────────────────────────────────────────────────
LINK_DOCS = sorted(set(glob.glob('docs/*.md') + glob.glob('docs/manuals/*.md') + glob.glob('docs/certification/*.md')
                       + ['README.md', 'CLAUDE.md', 'AGENTS.md', 'CHANGELOG.md', 'idem-console-admin/README.md', 'idem-sdk-java/README.md']))
for path in LINK_DOCS:
    if not os.path.exists(path):
        continue
    for i, l in lines(path):
        for m in re.finditer(r'\]\(([^)\s#]+)(#[^)]*)?\)', l):
            t = m.group(1)
            if re.match(r'^[a-z][a-z0-9+.-]*:', t) or t.startswith('<'):
                continue
            target = os.path.normpath(os.path.join(os.path.dirname(path), t))
            if not os.path.exists(target):
                err(path, i, f'[L] 링크 대상 없음: {t}')

if errors:
    print(f'docs-lint: 위반 {len(errors)}건 (빌드 버전 {V}, 시험 항목 {n_rows}행)')
    for e in errors:
        print('  ' + e)
    sys.exit(1)
print(f'docs-lint: OK — 버전 {V} 일치({len(VERSION_SITES)}곳), 시험 항목 {n_rows}행·집계 일치, 오류 코드·구 이름·링크 이상 없음 ({len(OPS_DOCS)}개 문서)')
