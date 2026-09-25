import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { get, put } from '../lib/api';
import type { TenantView } from '../lib/types';
import { isGlobalSystemAdmin, useAuth } from '../auth';
import { ErrorBox, Field, Section, fmt } from '../ui';

export function Tenants() {
  const { me } = useAuth();
  const [list, setList] = useState<TenantView[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [status, setStatus] = useState('ACTIVE');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => get<TenantView[]>('/tenants').then(setList).catch(setError), []);
  useEffect(() => { void load(); }, [load]);

  const save = async (e: FormEvent) => {
    e.preventDefault(); setBusy(true); setError(null);
    try { await put(`/tenants/${encodeURIComponent(code.trim())}`, { name: name.trim(), status }); setCode(''); setName(''); await load(); }
    catch (err) { setError(err); } finally { setBusy(false); }
  };

  return (
    <>
      {isGlobalSystemAdmin(me) && (
        <Section title="테넌트 추가·수정">
          <form onSubmit={save} className="grid3">
            <Field label="코드" hint="영문·숫자·_·-. 같은 코드면 수정"><input value={code} onChange={(e) => setCode(e.target.value)} required pattern="[A-Za-z0-9_\-]{2,64}" /></Field>
            <Field label="이름"><input value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} /></Field>
            <Field label="상태"><select value={status} onChange={(e) => setStatus(e.target.value)}><option>ACTIVE</option><option>INACTIVE</option></select></Field>
            <div style={{ alignSelf: 'end', marginBottom: 10 }}><button className="btn" disabled={busy}>저장</button></div>
          </form>
        </Section>
      )}
      <Section title="테넌트">
        <ErrorBox error={error} />
        <table>
          <thead><tr><th>코드</th><th>이름</th><th>상태</th><th>생성</th><th>수정</th></tr></thead>
          <tbody>
            {(list ?? []).map((t) => <tr key={t.code} className="click" onClick={() => { setCode(t.code); setName(t.name); setStatus(t.status); }}><td className="mono">{t.code}</td><td>{t.name}</td><td><span className={`pill ${t.status === 'ACTIVE' ? 'ok' : 'bad'}`}>{t.status}</span></td><td className="muted">{fmt(t.createdAt)}</td><td className="muted">{fmt(t.updatedAt)}</td></tr>)}
            {list && list.length === 0 && <tr><td colSpan={5} className="muted">테넌트가 없습니다 (기관은 DEFAULT 테넌트에 만들어집니다).</td></tr>}
          </tbody>
        </table>
      </Section>
    </>
  );
}
