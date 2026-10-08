import { useCallback, useEffect, useState } from 'react';
import { RULES, RULE_LABEL, anomalyQuery, detailsSummary, promotionVerdict } from '../lib/anomaly';
import { get, post } from '../lib/api';
import type { AnomalyFlagItem, AnomalyPage, AnomalyReview, AnomalyStats } from '../lib/types';
import { useAuth } from '../auth';
import { Alert, ErrorBox, Field, Section, fmt } from '../ui';

/** 1.1 감사 이상 탐지 — 관찰 모드. 플래그를 보고 검토(정탐/오탐/모름)를 남긴다. 검토 결과가 3개월 뒤 경보 승격 판단의 근거다. */
export function Anomalies() {
  const { me } = useAuth();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [rule, setRule] = useState('');
  const [agencyCode, setAgencyCode] = useState('');
  const [severity, setSeverity] = useState('');
  const [review, setReview] = useState('UNREVIEWED');
  const [page, setPage] = useState(0);
  const size = 50;
  const [data, setData] = useState<AnomalyPage | null>(null);
  const [stats, setStats] = useState<AnomalyStats | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);

  const query = useCallback(async (p = page) => {
    setError(null);
    try {
      const [d, s] = await Promise.all([
        get<AnomalyPage>(`/anomalies?${anomalyQuery({ from, to, rule, agencyCode, severity, review, page: p, size })}`),
        get<AnomalyStats>(`/anomalies/stats?days=90${agencyCode.trim() ? `&agencyCode=${encodeURIComponent(agencyCode.trim())}` : ''}`),
      ]);
      setData(d); setStats(s);
    } catch (e) { setError(e); }
  }, [from, to, rule, agencyCode, severity, review, page, size]);

  useEffect(() => { void query(0); // 첫 진입 // eslint-disable-line react-hooks/exhaustive-deps
  }, []);

  const go = (p: number) => { setPage(p); void query(p); };
  const verdict = async (f: AnomalyFlagItem, v: AnomalyReview) => {
    setBusy(true); setError(null);
    try { await post(`/anomalies/${encodeURIComponent(f.flagId)}/review`, { verdict: v, note: note.trim() || undefined }); setNote(''); setOpen(null); await query(page); }
    catch (e) { setError(e); } finally { setBusy(false); }
  };
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <>
      <Section title="감사 이상 징후 (관찰 모드)" actions={<button className="btn small" onClick={() => go(0)}>조회</button>}>
        <p className="muted">감사 기록이 저장된 뒤 비동기로 규칙 5개를 평가해 플래그만 남깁니다 — 경보·차단은 없습니다. 플래그마다 정탐/오탐을 남겨 주세요. 3개월 뒤 규칙별 정밀도로 경보 승격을 정합니다(docs/audit-anomaly.md).</p>
        {me?.tenantCode && <Alert kind="info">테넌트 관리자는 자기 테넌트 기관의 플래그만 볼 수 있습니다 — 기관 코드를 지정하세요.</Alert>}
        <div className="grid3">
          <Field label="시작"><input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="끝"><input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <Field label="규칙"><select value={rule} onChange={(e) => setRule(e.target.value)}><option value="">(전체)</option>{RULES.map((r) => <option key={r} value={r}>{RULE_LABEL[r]}</option>)}</select></Field>
          <Field label="기관 코드"><input value={agencyCode} onChange={(e) => setAgencyCode(e.target.value)} /></Field>
          <Field label="심각도"><select value={severity} onChange={(e) => setSeverity(e.target.value)}><option value="">(전체)</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select></Field>
          <Field label="검토"><select value={review} onChange={(e) => setReview(e.target.value)}><option value="">(전체)</option><option value="UNREVIEWED">미검토</option><option value="REVIEWED">검토됨</option><option value="TRUE_POSITIVE">정탐</option><option value="FALSE_POSITIVE">오탐</option><option value="UNSURE">모름</option></select></Field>
        </div>
        <ErrorBox error={error} />
        {data && (
          <>
            <p className="muted">총 {data.total}건 · {page + 1}/{pages} 쪽</p>
            <table>
              <thead><tr><th>시각</th><th>규칙</th><th>심각도</th><th>축</th><th>기관</th><th>사건</th><th>검토</th></tr></thead>
              <tbody>
                {data.items.map((it) => (
                  <>
                    <tr key={it.flagId} className="click" onClick={() => { setOpen(open === it.flagId ? null : it.flagId); setNote(''); }}>
                      <td className="muted" style={{ whiteSpace: 'nowrap' }}>{fmt(it.occurredAt)}</td>
                      <td>{RULE_LABEL[it.rule] ?? it.rule}</td>
                      <td><span className={`pill ${it.severity === 'HIGH' ? 'bad' : it.severity === 'MEDIUM' ? 'warn' : ''}`}>{it.severity} {it.score}</span></td>
                      <td className="mono">{it.subjectType}:{it.subject}</td>
                      <td className="mono">{it.agencyCode ?? ''}</td>
                      <td><span className="pill">{it.category}</span> <span className="mono">{it.action}</span></td>
                      <td>{it.review ? <span className={`pill ${it.review === 'TRUE_POSITIVE' ? 'bad' : it.review === 'FALSE_POSITIVE' ? 'ok' : 'warn'}`}>{it.review}</span> : <span className="muted">—</span>}</td>
                    </tr>
                    {open === it.flagId && (
                      <tr key={it.flagId + '-d'}><td colSpan={7}>
                        <dl className="kv">
                          <dt>근거</dt><dd className="mono">{detailsSummary(it.details)}</dd>
                          <dt>행위자 / IP</dt><dd className="mono">{it.actorId ?? '—'} / {it.sourceIp ?? '—'}</dd>
                          <dt>correlationId</dt><dd className="mono">{it.correlationId ?? '—'}</dd>
                          <dt>auditId</dt><dd className="mono">{it.auditId}</dd>
                          {it.review && <><dt>검토</dt><dd>{it.review} · {it.reviewedBy} · {fmt(it.reviewedAt)} {it.reviewNote ? `— ${it.reviewNote}` : ''}</dd></>}
                        </dl>
                        <div className="row" style={{ marginTop: 8 }}>
                          <input placeholder="검토 메모 (선택)" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} style={{ flex: 1 }} />
                          <button className="btn danger small" disabled={busy} onClick={() => void verdict(it, 'TRUE_POSITIVE')}>정탐</button>
                          <button className="btn secondary small" disabled={busy} onClick={() => void verdict(it, 'FALSE_POSITIVE')}>오탐</button>
                          <button className="btn secondary small" disabled={busy} onClick={() => void verdict(it, 'UNSURE')}>모름</button>
                        </div>
                      </td></tr>
                    )}
                  </>
                ))}
                {data.items.length === 0 && <tr><td colSpan={7} className="muted">플래그가 없습니다.</td></tr>}
              </tbody>
            </table>
            <div className="row" style={{ marginTop: 8 }}>
              <button className="btn secondary small" disabled={page === 0} onClick={() => go(page - 1)}>이전</button>
              <button className="btn secondary small" disabled={page + 1 >= pages} onClick={() => go(page + 1)}>다음</button>
            </div>
          </>
        )}
      </Section>
      {stats && (
        <Section title={`규칙별 기준선 — 최근 ${stats.days}일`}>
          <p className="muted">점수기 커서: 마지막 감사 시각 {fmt(stats.cursor.lastOccurredAt)} · 누적 {stats.cursor.scannedTotal ?? 0}행 · 갱신 {fmt(stats.cursor.updatedAt)}</p>
          <table>
            <thead><tr><th>규칙</th><th>플래그</th><th>정탐</th><th>오탐</th><th>모름</th><th>미검토</th><th>정밀도</th><th>판단</th></tr></thead>
            <tbody>
              {stats.byRule.map((r) => (
                <tr key={r.rule}>
                  <td>{RULE_LABEL[r.rule] ?? r.rule}</td><td>{r.total}</td><td>{r.truePositive}</td><td>{r.falsePositive}</td><td>{r.unsure}</td><td>{r.unreviewed}</td>
                  <td>{r.precision === null ? '—' : r.precision.toFixed(2)}</td>
                  <td><span className={`pill ${promotionVerdict(r) === '승격 후보' ? 'ok' : promotionVerdict(r) === '규칙 조정' ? 'warn' : ''}`}>{promotionVerdict(r)}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}
    </>
  );
}
