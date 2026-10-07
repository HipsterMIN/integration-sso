import { useCallback, useEffect, useState } from 'react';
import { auditSummaryQuery, useAiStatus } from '../lib/ai';
import { get } from '../lib/api';
import type { AiAuditSummary, AuditPage } from '../lib/types';
import { useAuth } from '../auth';
import { Alert, ErrorBox, Field, Section, fmt } from '../ui';

const CATEGORIES = ['', 'ADMIN', 'AGENCY', 'PROFILE', 'TENANT', 'AUTH', 'HANDOFF', 'SESSION', 'PROVISION', 'SYSTEM'];

export function Audit() {
  const { me } = useAuth();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [category, setCategory] = useState('');
  const [action, setAction] = useState('');
  const [actorId, setActorId] = useState('');
  const [agencyCode, setAgencyCode] = useState('');
  const [outcome, setOutcome] = useState('');
  const [page, setPage] = useState(0);
  const [size] = useState(50);
  const [data, setData] = useState<AuditPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [open, setOpen] = useState<string | null>(null);
  const ai = useAiStatus();
  const [summary, setSummary] = useState<AiAuditSummary | null>(null);
  const [summaryError, setSummaryError] = useState<unknown>(null);
  const [summarizing, setSummarizing] = useState(false);
  const summarize = async () => {
    setSummarizing(true); setSummaryError(null); setSummary(null);
    try { setSummary(await get<AiAuditSummary>(`/ai/audit-summary?${auditSummaryQuery({ from, to, category, action, agencyCode, outcome })}`)); }
    catch (e) { setSummaryError(e); } finally { setSummarizing(false); }
  };

  const query = useCallback(async (p = page) => {
    setError(null);
    const q = new URLSearchParams();
    if (from) q.set('from', new Date(from).toISOString());
    if (to) q.set('to', new Date(to).toISOString());
    for (const [k, v] of [['category', category], ['action', action.trim()], ['actorId', actorId.trim()], ['agencyCode', agencyCode.trim()], ['outcome', outcome]]) if (v) q.set(k, v);
    q.set('page', String(p)); q.set('size', String(size));
    try { setData(await get<AuditPage>(`/audit?${q}`)); } catch (e) { setError(e); }
  }, [from, to, category, action, actorId, agencyCode, outcome, page, size]);

  useEffect(() => { void query(0); /* 첫 진입 */ // eslint-disable-line react-hooks/exhaustive-deps
  }, []);

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;
  const go = (p: number) => { setPage(p); void query(p); };

  return (
    <Section title="감사 로그" actions={<div className="row"><button className="btn small" onClick={() => go(0)}>조회</button>{ai?.enabled && <button className="btn secondary small" disabled={summarizing} onClick={() => void summarize()}>{summarizing ? 'AI 요약 중…' : 'AI 요약'}</button>}</div>}>
      {me?.tenantCode && <Alert kind="info">테넌트 관리자는 자기 테넌트 기관의 기록만 볼 수 있습니다 — 기관 코드를 지정하세요.</Alert>}
      <div className="grid3">
        <Field label="시작"><input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label="끝"><input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
        <Field label="분류"><select value={category} onChange={(e) => setCategory(e.target.value)}>{CATEGORIES.map((c) => <option key={c} value={c}>{c || '(전체)'}</option>)}</select></Field>
        <Field label="사건(action)" hint="예: ADMIN_LOGIN_SUCCESS, PROFILE_UPDATED"><input value={action} onChange={(e) => setAction(e.target.value)} /></Field>
        <Field label="주체(actorId)"><input value={actorId} onChange={(e) => setActorId(e.target.value)} /></Field>
        <Field label="기관 코드"><input value={agencyCode} onChange={(e) => setAgencyCode(e.target.value)} /></Field>
        <Field label="결과"><select value={outcome} onChange={(e) => setOutcome(e.target.value)}><option value="">(전체)</option><option>SUCCESS</option><option>FAILURE</option></select></Field>
      </div>
      <ErrorBox error={error} />
      <ErrorBox error={summaryError} />
      {summary && (
        <div className="card" style={{ background: '#f8fafc' }}>
          <div className="card-head"><h2>AI 요약 — 모델 {summary.model}</h2><span className="muted">집계 {summary.digest.rows}행(전체 {summary.digest.total}건) · 표본 {summary.digest.sample.length}행만 LLM 에 보냈습니다 (IP·metadata 제외, 행위자 마스킹)</span></div>
          <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', margin: '0 0 8px' }}>{summary.summary}</pre>
          <div className="grid3">
            <div><b>분류</b>{Object.entries(summary.digest.byCategory).map(([k, n]) => <div key={k} className="mono">{k}: {n}</div>)}</div>
            <div><b>결과</b>{Object.entries(summary.digest.byOutcome).map(([k, n]) => <div key={k} className="mono">{k}: {n}</div>)}</div>
            <div><b>실패 상위</b>{summary.digest.topFailures.map((f) => <div key={f.action + f.detail} className="mono">{f.action} ×{f.n} {f.detail ? `— ${f.detail}` : ''}</div>)}</div>
          </div>
        </div>
      )}
      {data && (
        <>
          <p className="muted">총 {data.total}건 · {page + 1}/{pages} 쪽</p>
          <table>
            <thead><tr><th>시각</th><th>분류 / 사건</th><th>주체</th><th>대상</th><th>기관</th><th>결과</th><th>IP</th></tr></thead>
            <tbody>
              {data.items.map((it) => (
                <>
                  <tr key={it.auditId} className="click" onClick={() => setOpen(open === it.auditId ? null : it.auditId)}>
                    <td className="muted" style={{ whiteSpace: 'nowrap' }}>{fmt(it.occurredAt)}</td>
                    <td><span className="pill">{it.category}</span> <span className="mono">{it.action}</span></td>
                    <td className="mono">{it.actorType}:{it.actorId}</td>
                    <td className="mono">{it.resourceType ? `${it.resourceType}:${it.resourceId ?? ''}` : ''}</td>
                    <td className="mono">{it.agencyCode ?? ''}</td>
                    <td><span className={`pill ${it.outcome === 'SUCCESS' ? 'ok' : 'bad'}`}>{it.outcome}</span></td>
                    <td className="mono">{it.sourceIp ?? ''}</td>
                  </tr>
                  {open === it.auditId && (
                    <tr key={it.auditId + '-d'}><td colSpan={7}>
                      <dl className="kv">
                        <dt>auditId</dt><dd className="mono">{it.auditId}</dd>
                        <dt>correlationId</dt><dd className="mono">{it.correlationId ?? '—'}</dd>
                        <dt>상세</dt><dd>{it.outcomeDetail ?? '—'}</dd>
                        <dt>metadata</dt><dd className="mono">{it.metadata ?? '—'}</dd>
                      </dl>
                    </td></tr>
                  )}
                </>
              ))}
              {data.items.length === 0 && <tr><td colSpan={7} className="muted">기록이 없습니다.</td></tr>}
            </tbody>
          </table>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="btn secondary small" disabled={page === 0} onClick={() => go(page - 1)}>이전</button>
            <button className="btn secondary small" disabled={page + 1 >= pages} onClick={() => go(page + 1)}>다음</button>
          </div>
        </>
      )}
    </Section>
  );
}
