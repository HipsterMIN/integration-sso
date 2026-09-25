import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { get, post, put } from '../lib/api';
import type { AdminStatus, AdminView, Role } from '../lib/types';
import { useAuth } from '../auth';
import { Alert, ErrorBox, Field, Secret, Section, fmt } from '../ui';

const ROLES: Role[] = ['SYSTEM_ADMIN', 'POLICY_ADMIN', 'AUDITOR'];

export function Admins() {
  const { me } = useAuth();
  const [list, setList] = useState<AdminView[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [created, setCreated] = useState<{ username: string; temporaryPassword: string } | null>(null);
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [role, setRole] = useState<Role>('AUDITOR');
  const [tenantCode, setTenantCode] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => get<AdminView[]>('/admins').then(setList).catch(setError), []);
  useEffect(() => { void load(); }, [load]);

  const create = async (e: FormEvent) => {
    e.preventDefault(); setBusy(true); setError(null); setCreated(null);
    try {
      const r = await post<{ admin: AdminView; temporaryPassword: string }>('/admins', { username: username.trim(), displayName: displayName.trim() || null, role, tenantCode: tenantCode.trim() || null });
      setCreated({ username: r.admin.username, temporaryPassword: r.temporaryPassword });
      setUsername(''); setDisplayName(''); setTenantCode('');
      await load();
    } catch (err) { setError(err); } finally { setBusy(false); }
  };

  return (
    <>
      <Section title="관리자 추가">
        <ErrorBox error={error} />
        {created && <Secret label={`${created.username} 임시 비밀번호`} value={created.temporaryPassword} note="지금만 표시 — 첫 로그인에서 변경·2단계 등록을 요구합니다" />}
        <form onSubmit={create} className="grid3">
          <Field label="사용자명"><input value={username} onChange={(e) => setUsername(e.target.value)} required pattern="[A-Za-z0-9._\-]{3,64}" /></Field>
          <Field label="표시 이름"><input value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></Field>
          <Field label="역할"><select value={role} onChange={(e) => setRole(e.target.value as Role)}>{ROLES.map((r) => <option key={r}>{r}</option>)}</select></Field>
          <Field label="테넌트" hint="비우면 전역. 지정하면 그 테넌트의 기관만 본다"><input value={tenantCode} onChange={(e) => setTenantCode(e.target.value)} /></Field>
          <div style={{ alignSelf: 'end', marginBottom: 10 }}><button className="btn" disabled={busy}>추가</button></div>
        </form>
      </Section>
      <Section title="관리자">
        <table>
          <thead><tr><th>사용자명</th><th>이름</th><th>역할</th><th>테넌트</th><th>상태</th><th>2단계</th><th>마지막 로그인</th><th>작업</th></tr></thead>
          <tbody>{(list ?? []).map((a) => <Row key={a.adminId} a={a} self={a.adminId === me?.adminId} onChange={load} />)}</tbody>
        </table>
      </Section>
    </>
  );
}

function Row({ a, self, onChange }: { a: AdminView; self: boolean; onChange: () => Promise<void> }) {
  const [role, setRole] = useState<Role>(a.role);
  const [status, setStatus] = useState<AdminStatus>(a.status);
  const [tenantCode, setTenantCode] = useState(a.tenantCode ?? '');
  const [error, setError] = useState<unknown>(null);
  const [temp, setTemp] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const dirty = role !== a.role || status !== a.status || tenantCode !== (a.tenantCode ?? '');

  const run = async (fn: () => Promise<void>) => {
    setBusy(true); setError(null); setMsg(null);
    try { await fn(); await onChange(); } catch (e) { setError(e); } finally { setBusy(false); }
  };
  const save = () => run(async () => { await put(`/admins/${a.adminId}`, { role, status, tenantCode: tenantCode.trim() || null }); setMsg('저장'); });
  const reset = () => { if (confirm(`${a.username} 의 비밀번호를 재설정할까요? 세션이 끝나고 임시 비밀번호가 한 번 표시됩니다.`)) void run(async () => { const r = await post<{ temporaryPassword: string }>(`/admins/${a.adminId}/reset-password`); setTemp(r.temporaryPassword); }); };
  const unlock = () => run(async () => { await post(`/admins/${a.adminId}/unlock`); setMsg('잠금 해제'); });
  const resetMfa = () => { if (confirm(`${a.username} 의 2단계를 초기화할까요? 다음 로그인에서 다시 등록합니다.`)) void run(async () => { await post(`/admins/${a.adminId}/reset-mfa`); setMsg('2단계 초기화'); }); };
  const locked = a.status === 'LOCKED' || (a.lockedUntil && new Date(a.lockedUntil) > new Date());

  return (
    <>
      <tr>
        <td className="mono">{a.username}{self && <span className="muted"> (나)</span>}</td>
        <td>{a.displayName ?? ''}</td>
        <td><select value={role} disabled={self} onChange={(e) => setRole(e.target.value as Role)}>{ROLES.map((r) => <option key={r}>{r}</option>)}</select></td>
        <td><input style={{ width: 110 }} value={tenantCode} disabled={self} onChange={(e) => setTenantCode(e.target.value)} /></td>
        <td><select value={status} disabled={self} onChange={(e) => setStatus(e.target.value as AdminStatus)}><option>ACTIVE</option><option>LOCKED</option><option>DISABLED</option></select>{locked && <span className="pill bad" style={{ marginLeft: 4 }}>잠김</span>}</td>
        <td>{a.totpEnrolled ? <span className="pill ok">등록</span> : <span className="pill warn">미등록</span>}{a.mustChangePassword && <span className="pill warn" style={{ marginLeft: 4 }}>비번 변경 필요</span>}</td>
        <td className="muted">{fmt(a.lastLoginAt)}</td>
        <td className="row">
          <button className="btn small" disabled={busy || !dirty || self} onClick={() => void save()}>저장</button>
          <button className="btn secondary small" disabled={busy || self} onClick={reset}>비번 재설정</button>
          <button className="btn secondary small" disabled={busy || !locked} onClick={() => void unlock()}>잠금 해제</button>
          <button className="btn secondary small" disabled={busy || self} onClick={resetMfa}>2단계 초기화</button>
        </td>
      </tr>
      {(error || temp || msg) && (
        <tr><td colSpan={8}>
          <ErrorBox error={error} />
          {msg && <Alert kind="ok">{msg}</Alert>}
          {temp && <Secret label={`${a.username} 임시 비밀번호`} value={temp} />}
        </td></tr>
      )}
    </>
  );
}
