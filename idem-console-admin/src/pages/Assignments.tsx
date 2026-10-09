import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError, del, get, post } from '../lib/api';
import {
  PAGE_SIZE, STATUS_LABEL, assignmentsPath, emptyAssign, emptyRole, listQuery, pageCount, reasonQuery, rolesPath, toAssignBody, toRoleBody,
  userRolesPath, validateAssign, validateRole, toExpiresAt, type AssignForm, type RoleForm,
} from '../lib/assignments';
import type { Assignment, AssignmentPage, RoleItem, UserRole } from '../lib/types';
import { canWrite, useAuth } from '../auth';
import { Alert, ErrorBox, Field, Section, fmt } from '../ui';

/**
 * 1.1.1 G1-3 — 할당 관리 카드: 서비스의 사용자 할당 목록(페이징)·직접 할당·해제, 역할(그룹) 카탈로그·생성, 사용자별 역할 부여·회수.
 * 상태는 idem-authz 가 가진다(hub 는 범위·감사만). authz 가 꺼진 설치본은 E-IDO-116 으로 안내만 한다.
 */
export function AssignmentsCard({ serviceCode }: { serviceCode: string }) {
  const { me } = useAuth();
  const write = canWrite(me);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<AssignmentPage | null>(null);
  const [roles, setRoles] = useState<RoleItem[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setError(null);
    try {
      const [p, r] = await Promise.all([
        get<AssignmentPage>(assignmentsPath(serviceCode) + listQuery(page, PAGE_SIZE)),
        get<RoleItem[]>(rolesPath(serviceCode)),
      ]);
      setData(p); setRoles(r);
    } catch (e) { setError(e); }
  }, [serviceCode, page]);
  useEffect(() => { void refresh(); }, [refresh]);

  const run = async (label: string, work: () => Promise<void>) => {
    setBusy(true); setError(null); setNotice(null);
    try { await work(); setNotice(label); await refresh(); }
    catch (e) { setError(e); } finally { setBusy(false); }
  };

  const unassign = (a: Assignment) => {
    const reason = prompt(`${a.qimUserId} 의 할당을 해제합니다. 사유(선택):`);
    if (reason === null) return;
    void run(`${a.qimUserId} 할당 해제`, async () => { await del<void>(assignmentsPath(serviceCode, a.qimUserId) + reasonQuery(reason)); if (selected === a.qimUserId) setSelected(null); });
  };

  const pages = data ? pageCount(data) : 1;
  const unavailable = error instanceof ApiError && error.code === 'E-IDO-116';

  return (
    <Section title="할당 관리 — 사용자·역할 (1.1.1)" actions={<span className="muted">{data ? `총 ${data.total}명` : '…'}</span>}>
      <ErrorBox error={error} />
      {unavailable && <p className="muted">이 설치본은 idem-authz 가 꺼져 있습니다(<span className="mono">IDEM_HUB_AUTHZ_ENABLED</span>). 할당 관리는 authz 를 켠 설치본에서만 씁니다.</p>}
      {notice && <Alert kind="ok">{notice}</Alert>}
      {write && !unavailable && <AssignForm busy={busy} onSubmit={(f) => run(`${f.qimUserId.trim()} 할당`, async () => { await post<Assignment>(assignmentsPath(serviceCode), toAssignBody(f)); })} />}
      <table>
        <thead><tr><th>사용자 ID</th><th>상태</th><th>출처</th><th>부여</th><th>만료</th><th>역할</th>{write && <th></th>}</tr></thead>
        <tbody>
          {(data?.items ?? []).map((a) => (
            <tr key={a.qimUserId} className={selected === a.qimUserId ? 'selected' : undefined}>
              <td className="mono">{a.qimUserId}</td>
              <td><span className={`pill ${a.status === 'ACTIVE' ? 'ok' : 'warn'}`}>{STATUS_LABEL[a.status] ?? a.status}</span></td>
              <td>{a.source ?? '—'}</td>
              <td className="muted">{fmt(a.grantedAt)}{a.grantedBy ? ` · ${a.grantedBy}` : ''}</td>
              <td className="muted">{a.expiresAt ? fmt(a.expiresAt) : '무기한'}</td>
              <td><button type="button" className="btn secondary small" onClick={() => setSelected(selected === a.qimUserId ? null : a.qimUserId)}>{selected === a.qimUserId ? '닫기' : '역할'}</button></td>
              {write && <td><button type="button" className="btn danger small" disabled={busy} onClick={() => unassign(a)}>해제</button></td>}
            </tr>
          ))}
          {data && data.items.length === 0 && <tr><td colSpan={write ? 7 : 6} className="muted">할당된 사용자가 없습니다. 위 폼으로 직접 할당하거나, 기관 SCIM·웹훅 연동이 채웁니다.</td></tr>}
        </tbody>
      </table>
      {data && pages > 1 && (
        <div className="row" style={{ marginTop: 8 }}>
          <button type="button" className="btn secondary small" disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
          <span className="muted">{page + 1} / {pages}</span>
          <button type="button" className="btn secondary small" disabled={!data.hasNext} onClick={() => setPage(page + 1)}>다음</button>
        </div>
      )}
      {selected && !unavailable && <UserRoles serviceCode={serviceCode} qimUserId={selected} roles={roles ?? []} write={write} />}
      {!unavailable && <RolesCatalog serviceCode={serviceCode} roles={roles} write={write} onChange={refresh} />}
    </Section>
  );
}

function AssignForm({ busy, onSubmit }: { busy: boolean; onSubmit: (f: AssignForm) => Promise<void> }) {
  const [f, setF] = useState<AssignForm>(emptyAssign());
  const [violations, setViolations] = useState<string[]>([]);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const v = validateAssign(f); setViolations(v); if (v.length) return;
    await onSubmit(f); setF(emptyAssign());
  };
  return (
    <form onSubmit={submit} className="subform">
      <div className="grid3">
        <Field label="사용자 ID (qimUserId)" hint="registry 사용자 ID — 영숫자·_ . : -"><input value={f.qimUserId} onChange={(e) => setF({ ...f, qimUserId: e.target.value })} required /></Field>
        <Field label="만료 (선택)" hint="비우면 무기한"><input type="datetime-local" value={f.expiresAt} onChange={(e) => setF({ ...f, expiresAt: e.target.value })} /></Field>
        <Field label="사유 (선택)"><input value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} maxLength={500} /></Field>
      </div>
      {violations.length > 0 && <Alert kind="error"><ul>{violations.map((v) => <li key={v}>{v}</li>)}</ul></Alert>}
      <button className="btn small" disabled={busy}>직접 할당</button>
    </form>
  );
}

function RolesCatalog({ serviceCode, roles, write, onChange }: { serviceCode: string; roles: RoleItem[] | null; write: boolean; onChange: () => Promise<void> }) {
  const [f, setF] = useState<RoleForm>(emptyRole());
  const [violations, setViolations] = useState<string[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const v = validateRole(f); setViolations(v); if (v.length) return;
    setBusy(true); setError(null);
    try { await post<RoleItem>(rolesPath(serviceCode), toRoleBody(f)); setF(emptyRole()); setOpen(false); await onChange(); }
    catch (err) { setError(err); } finally { setBusy(false); }
  };
  return (
    <div className="subcard">
      <header className="row"><h3>역할(그룹) 카탈로그 {roles ? `· ${roles.length}` : ''}</h3>{write && <button type="button" className="btn secondary small" onClick={() => setOpen(!open)}>{open ? '닫기' : '역할 만들기'}</button>}</header>
      <ErrorBox error={error} />
      {open && write && (
        <form onSubmit={submit} className="subform">
          <div className="grid3">
            <Field label="역할 코드" hint="영숫자·_ . : - (예: VIEWER)"><input value={f.roleCode} onChange={(e) => setF({ ...f, roleCode: e.target.value })} required /></Field>
            <Field label="이름"><input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required maxLength={200} /></Field>
            <Field label="설명 (선택)"><input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} maxLength={1000} /></Field>
          </div>
          {violations.length > 0 && <Alert kind="error"><ul>{violations.map((v) => <li key={v}>{v}</li>)}</ul></Alert>}
          <button className="btn small" disabled={busy}>만들기</button>
        </form>
      )}
      {roles && roles.length > 0 && (
        <table>
          <thead><tr><th>코드</th><th>이름</th><th>설명</th><th>부여 가능</th><th>생성</th></tr></thead>
          <tbody>{roles.map((r) => <tr key={r.roleCode}><td className="mono">{r.roleCode}</td><td>{r.name}</td><td className="muted">{r.description ?? ''}</td><td><span className={`pill ${r.assignable ? 'ok' : 'warn'}`}>{r.assignable ? '가능' : '불가'}</span></td><td className="muted">{fmt(r.createdAt)}</td></tr>)}</tbody>
        </table>
      )}
      {roles && roles.length === 0 && <p className="muted">역할이 없습니다. 역할은 서비스 안의 그룹(예: VIEWER·EDITOR)으로, 사용자에게 부여하면 SDK <span className="mono">roles</span> 클레임으로 내려갑니다.</p>}
    </div>
  );
}

function UserRoles({ serviceCode, qimUserId, roles, write }: { serviceCode: string; qimUserId: string; roles: RoleItem[]; write: boolean }) {
  const [list, setList] = useState<UserRole[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [roleCode, setRoleCode] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [reason, setReason] = useState('');
  const refresh = useCallback(() => get<UserRole[]>(userRolesPath(serviceCode, qimUserId)).then(setList).catch(setError), [serviceCode, qimUserId]);
  useEffect(() => { setList(null); setError(null); void refresh(); }, [refresh]);
  const held = new Set((list ?? []).filter((r) => r.status === 'ACTIVE').map((r) => r.roleCode));
  const grantable = roles.filter((r) => r.assignable && !held.has(r.roleCode));

  const grant = async (e: FormEvent) => {
    e.preventDefault();
    const exp = toExpiresAt(expiresAt);
    if (!roleCode) { setError(new Error('역할을 고르세요')); return; }
    if (exp.error) { setError(new Error(exp.error)); return; }
    setBusy(true); setError(null);
    try {
      const body: Record<string, unknown> = { roleCode };
      if (exp.value) body.expiresAt = exp.value;
      if (reason.trim()) body.reason = reason.trim();
      await post<UserRole>(userRolesPath(serviceCode, qimUserId), body);
      setRoleCode(''); setExpiresAt(''); setReason(''); await refresh();
    } catch (err) { setError(err); } finally { setBusy(false); }
  };
  const revoke = (r: UserRole) => {
    const why = prompt(`${qimUserId} 의 역할 ${r.roleCode} 을 회수합니다. 사유(선택):`);
    if (why === null) return;
    setBusy(true); setError(null);
    del<void>(userRolesPath(serviceCode, qimUserId, r.roleCode) + reasonQuery(why)).then(refresh).catch(setError).finally(() => setBusy(false));
  };

  return (
    <div className="subcard">
      <header className="row"><h3>역할 — <span className="mono">{qimUserId}</span></h3></header>
      <ErrorBox error={error} />
      {write && (
        <form onSubmit={grant} className="subform">
          <div className="grid3">
            <Field label="역할">
              <select value={roleCode} onChange={(e) => setRoleCode(e.target.value)} required>
                <option value="">— 고르세요 —</option>
                {grantable.map((r) => <option key={r.roleCode} value={r.roleCode}>{r.roleCode} · {r.name}</option>)}
              </select>
            </Field>
            <Field label="만료 (선택)"><input type="datetime-local" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} /></Field>
            <Field label="사유 (선택)"><input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></Field>
          </div>
          <button className="btn small" disabled={busy || grantable.length === 0}>역할 부여</button>
          {grantable.length === 0 && roles.length > 0 && <span className="muted"> 부여할 수 있는 역할이 더 없습니다.</span>}
        </form>
      )}
      <table>
        <thead><tr><th>역할</th><th>상태</th><th>부여</th><th>만료</th><th>출처</th>{write && <th></th>}</tr></thead>
        <tbody>
          {(list ?? []).map((r) => (
            <tr key={r.id ?? r.roleCode}>
              <td className="mono">{r.roleCode}</td>
              <td><span className={`pill ${r.status === 'ACTIVE' ? 'ok' : 'warn'}`}>{STATUS_LABEL[r.status] ?? r.status}</span></td>
              <td className="muted">{fmt(r.grantedAt)}{r.grantedBy ? ` · ${r.grantedBy}` : ''}</td>
              <td className="muted">{r.expiresAt ? fmt(r.expiresAt) : '무기한'}</td>
              <td>{r.source ?? '—'}</td>
              {write && <td>{r.status === 'ACTIVE' && <button type="button" className="btn danger small" disabled={busy} onClick={() => revoke(r)}>회수</button>}</td>}
            </tr>
          ))}
          {list && list.length === 0 && <tr><td colSpan={write ? 6 : 5} className="muted">부여된 역할이 없습니다.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
