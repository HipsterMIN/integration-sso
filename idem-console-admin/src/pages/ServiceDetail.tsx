import { useCallback, useEffect, useState } from 'react';
import { ApiError, get, post, put } from '../lib/api';
import { emptyForm, fromProfile } from '../lib/profile';
import type { Agency, OidcClientSecret, OidcClientStatus, PolicySimulation, Profile } from '../lib/types';
import { canWrite, useAuth } from '../auth';
import { href, navigate } from '../router';
import { Alert, ErrorBox, Field, Secret, Section, fmt } from '../ui';
import { ProfileForm } from './ProfileForm';

export function ServiceDetail({ code }: { code: string }) {
  const { me } = useAuth();
  const isNew = code === 'new';
  const [profile, setProfile] = useState<Profile | null>(null);
  const [agency, setAgency] = useState<Agency | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [loaded, setLoaded] = useState(isNew);
  const [saveError, setSaveError] = useState<unknown>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [formKey, setFormKey] = useState(0);

  const load = useCallback(async () => {
    if (isNew) return;
    setLoadError(null);
    try {
      const [p, a] = await Promise.all([
        get<Profile>(`/services/${encodeURIComponent(code)}/profile`).catch((e) => { if (e instanceof ApiError && e.status === 404) return null; throw e; }),
        get<Agency>(`/agencies/${encodeURIComponent(code)}`).catch((e) => { if (e instanceof ApiError && e.status === 404) return null; throw e; }),
      ]);
      setProfile(p); setAgency(a); setFormKey((k) => k + 1);
    } catch (e) { setLoadError(e); } finally { setLoaded(true); }
  }, [code, isNew]);

  useEffect(() => { void load(); }, [load]);

  const save = async (doc: Profile, reason: string) => {
    setBusy(true); setSaveError(null); setSaved(null);
    const svc = (doc.service as Record<string, unknown> | undefined)?.code;
    const target = isNew ? String(svc ?? '') : code;
    try {
      const r = await put<Profile>(`/services/${encodeURIComponent(target)}/profile`, doc, reason ? { 'X-Change-Reason': reason } : undefined);
      setSaved(`저장했습니다 (${target})`);
      if (isNew) navigate(`/services/${encodeURIComponent(target)}`);
      else { setProfile(r); await load(); }
    } catch (e) { setSaveError(e); } finally { setBusy(false); }
  };

  if (!loaded) return <p className="muted">불러오는 중…</p>;
  const form = profile ? fromProfile(profile) : emptyForm(isNew ? '' : code);
  const type = (profile?.protocol as Record<string, unknown> | undefined)?.type as string | undefined;

  return (
    <>
      <p><a href={href('/services')}>← 기관 목록</a></p>
      <Section title={isNew ? '새 기관 온보딩' : `기관 ${code}`}>
        <ErrorBox error={loadError} />
        {!isNew && !profile && !loadError && <Alert kind="warn">저장된 프로파일이 없습니다(구 등록 API 로 만든 기관). 아래 폼으로 프로파일을 저장하면 정식화됩니다.</Alert>}
        {saved && <Alert kind="ok">{saved}</Alert>}
        <ErrorBox error={saveError} />
        {canWrite(me)
          ? <ProfileForm key={formKey} initial={form} base={profile ?? {}} codeLocked={!isNew} busy={busy} onSubmit={save} />
          : <pre className="mono">{JSON.stringify(profile, null, 2)}</pre>}
      </Section>
      {!isNew && agency && <StatusCard agency={agency} onChange={load} />}
      {!isNew && type === 'OIDC_RP' && <OidcCard code={code} />}
      {!isNew && profile && <SimulateCard code={code} />}
      {!isNew && agency && <HistoryCard code={code} />}
    </>
  );
}

function StatusCard({ agency, onChange }: { agency: Agency; onChange: () => Promise<void> }) {
  const { me } = useAuth();
  const [error, setError] = useState<unknown>(null);
  const [key, setKey] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const act = async (path: string) => {
    setBusy(true); setError(null);
    try {
      const r = await post<Record<string, string>>(`/agencies/${encodeURIComponent(agency.agencyCode)}/${path}`);
      if (path === 'rotate-key') setKey(r.newApiKey);
      await onChange();
    } catch (e) { setError(e); } finally { setBusy(false); }
  };
  return (
    <Section title="상태·API 키" actions={canWrite(me) && (
      <div className="row">
        {agency.active ? <button className="btn danger small" disabled={busy} onClick={() => void act('deactivate')}>비활성화</button>
          : <button className="btn small" disabled={busy} onClick={() => void act('activate')}>활성화</button>}
        <button className="btn secondary small" disabled={busy} onClick={() => { if (confirm('API 키를 회전하면 기존 키는 즉시 무효가 됩니다. 계속할까요?')) void act('rotate-key'); }}>API 키 회전</button>
      </div>
    )}>
      <ErrorBox error={error} />
      {key && <Secret label="새 API 키 (기관에 전달)" value={key} />}
      <dl className="kv">
        <dt>상태</dt><dd><span className={`pill ${agency.active ? 'ok' : 'bad'}`}>{agency.active ? 'ACTIVE' : 'INACTIVE'}</span></dd>
        <dt>연동</dt><dd>{agency.integrationType ?? '—'}</dd>
        <dt>인증수준·정책</dt><dd>{agency.minAuthLevel ?? '—'} · v{agency.policyVersion ?? '—'}</dd>
        <dt>일 조회 한도</dt><dd>{agency.dailyLookupLimit ?? '—'}</dd>
        <dt>생성 / 수정</dt><dd>{fmt(agency.createdAt)} / {fmt(agency.updatedAt)}</dd>
      </dl>
    </Section>
  );
}

function OidcCard({ code }: { code: string }) {
  const { me } = useAuth();
  const [status, setStatus] = useState<OidcClientStatus | null>(null);
  const [secret, setSecret] = useState<OidcClientSecret | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const refresh = useCallback(() => get<OidcClientStatus>(`/services/${encodeURIComponent(code)}/oidc-client`).then(setStatus).catch(setError), [code]);
  useEffect(() => { void refresh(); }, [refresh]);
  const rotate = async () => {
    if (!confirm('client secret 을 회전하면 기관 RP 의 기존 secret 은 즉시 무효가 됩니다. 계속할까요?')) return;
    setBusy(true); setError(null);
    try { setSecret(await post<OidcClientSecret>(`/services/${encodeURIComponent(code)}/oidc-client/secret`)); await refresh(); }
    catch (e) { setError(e); } finally { setBusy(false); }
  };
  return (
    <Section title="표준 OIDC client (Keycloak 은 보이지 않는다)" actions={canWrite(me) && <button className="btn secondary small" disabled={busy} onClick={() => void rotate()}>client secret 회전</button>}>
      <ErrorBox error={error} />
      {secret && (
        <>
          <Secret label="client_secret" value={secret.clientSecret} />
          <p className="muted">기관에는 issuer · client_id · client_secret 셋만 전달합니다. 나머지는 RP 가 Discovery 로 찾습니다.</p>
        </>
      )}
      {status && (
        <dl className="kv">
          <dt>client_id</dt><dd className="mono">{status.clientId}</dd>
          <dt>issuer</dt><dd className="mono">{status.issuer}</dd>
          <dt>discovery</dt><dd className="mono">{status.discoveryUrl}</dd>
          <dt>프로비저닝</dt><dd><span className={`pill ${status.provisioned ? 'ok' : 'bad'}`}>{status.provisioned ? '있음' : '없음'}</span> <span className={`pill ${status.enabled ? 'ok' : 'warn'}`}>{status.enabled ? 'enabled' : 'disabled'}</span></dd>
          <dt>redirect URIs</dt><dd>{status.redirectUris.length ? status.redirectUris.map((u) => <div key={u} className="mono">{u}</div>) : '—'}</dd>
        </dl>
      )}
    </Section>
  );
}

function SimulateCard({ code }: { code: string }) {
  const [authLevel, setAuthLevel] = useState('L1');
  const [provider, setProvider] = useState('MOCK');
  const [userStatus, setUserStatus] = useState('ACTIVE');
  const [assigned, setAssigned] = useState(true);
  const [result, setResult] = useState<PolicySimulation | null>(null);
  const [error, setError] = useState<unknown>(null);
  const run = async () => {
    setError(null);
    try { setResult(await post<PolicySimulation>(`/services/${encodeURIComponent(code)}/policy/simulate`, { authLevel, providerCode: provider, userStatus, assigned })); }
    catch (e) { setError(e); }
  };
  return (
    <Section title="정책 시뮬레이션" actions={<button className="btn secondary small" onClick={() => void run()}>판정</button>}>
      <div className="grid3">
        <Field label="인증수준"><select value={authLevel} onChange={(e) => setAuthLevel(e.target.value)}><option>L1</option><option>L2</option><option>L3</option></select></Field>
        <Field label="제공자"><input value={provider} onChange={(e) => setProvider(e.target.value)} /></Field>
        <Field label="사용자 상태"><select value={userStatus} onChange={(e) => setUserStatus(e.target.value)}><option>ACTIVE</option><option>SUSPENDED</option><option>WITHDRAWN</option><option>DORMANT</option></select></Field>
      </div>
      <label><input type="checkbox" checked={assigned} onChange={(e) => setAssigned(e.target.checked)} /> 서비스에 할당된 사용자</label>
      <ErrorBox error={error} />
      {result && (
        <>
          <p><span className={`pill ${result.allowed ? 'ok' : 'bad'}`}>{result.allowed ? '허용' : '거부'}</span></p>
          <table><thead><tr><th>규칙</th><th>결과</th><th>사유</th></tr></thead>
            <tbody>{result.decisions.map((d, i) => <tr key={i}><td className="mono">{d.rule}</td><td><span className={`pill ${d.outcome === 'DENY' ? 'bad' : d.outcome === 'ALLOW' ? 'ok' : ''}`}>{d.outcome}</span></td><td>{d.errorCode ? `${d.errorCode} ` : ''}{d.reason ?? ''}</td></tr>)}</tbody></table>
        </>
      )}
    </Section>
  );
}

function HistoryCard({ code }: { code: string }) {
  const [rows, setRows] = useState<Record<string, unknown>[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => { get<Record<string, unknown>[]>(`/agencies/${encodeURIComponent(code)}/history`).then(setRows).catch(setError); }, [code]);
  if (error) return <Section title="변경 이력"><ErrorBox error={error} /></Section>;
  if (!rows || rows.length === 0) return null;
  const cols = Object.keys(rows[0]).slice(0, 8);
  return (
    <Section title="변경 이력">
      <table><thead><tr>{cols.map((c) => <th key={c}>{c}</th>)}</tr></thead>
        <tbody>{rows.slice(0, 50).map((r, i) => <tr key={i}>{cols.map((c) => <td key={c} className="mono">{typeof r[c] === 'object' ? JSON.stringify(r[c]) : String(r[c] ?? '')}</td>)}</tr>)}</tbody></table>
    </Section>
  );
}
