import { useState } from 'react';
import { kvRows, useAiStatus } from '../lib/ai';
import { get } from '../lib/api';
import type { AiIncidentSummary } from '../lib/types';
import { Alert, ErrorBox, Section } from '../ui';

/** 1.1 AI 운영 보조 — 장애 요약(전역 관리자). 운영 스냅샷(건수·상태·지표)은 그대로 보이고 LLM 은 그것을 읽어 판정·확인 순서를 적는다. */
export function Ops() {
  const ai = useAiStatus();
  const [result, setResult] = useState<AiIncidentSummary | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true); setError(null);
    try { setResult(await get<AiIncidentSummary>('/ai/incident-summary')); } catch (e) { setError(e); } finally { setBusy(false); }
  };
  if (!ai) return <p className="muted">불러오는 중…</p>;
  if (!ai.enabled) return <Section title="AI 운영 보조"><Alert kind="info">이 설치본에서는 꺼져 있습니다 — {ai.reason ?? 'IDEM_HUB_AI_ENABLED=false'}. 켜는 법은 docs/install-inputs.md 의 AI 운영 보조 행.</Alert></Section>;
  const snap = result?.snapshot;
  const table = (title: string, rows: [string, string][]) => (
    <div><b>{title}</b>{rows.length === 0 ? <div className="muted">—</div> : rows.map(([k, v]) => <div key={k} className="mono">{k}: {v}</div>)}</div>
  );
  return (
    <>
      <Section title="장애 요약 (AI)" actions={<button className="btn small" disabled={busy} onClick={() => void run()}>{busy ? '요약 중…' : '지금 상태 요약'}</button>}>
        <p className="muted">모델 {ai.model} @ {ai.endpointHost}. hub 가 가진 운영 신호(health · 웹훅/SCIM 아웃박스 · SLO 재시도 큐 · 감사 실패 건수 · 감사 유실 지표)만 보냅니다 — 사용자 데이터는 가지 않습니다. 인증 경로와 무관합니다.</p>
        <ErrorBox error={error} />
        {result && <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit' }}>{result.summary}</pre>}
      </Section>
      {snap && (
        <Section title={`운영 스냅샷 — ${snap.at}`}>
          <div className="grid3">
            {table('health', kvRows(snap.health))}
            {table('감사 FAILURE 1h / 24h', [...kvRows(snap.auditFailures1h).map(([k, v]) => [k + ' (1h)', v] as [string, string]), ...kvRows(snap.auditFailures24h).map(([k, v]) => [k + ' (24h)', v] as [string, string])])}
            {table('지표', kvRows(snap.metrics))}
            {table('웹훅 아웃박스', kvRows(snap.webhookOutbox))}
            {table('SCIM 아웃박스', kvRows(snap.scimOutbox))}
            {table('SLO IdP 재시도', kvRows(snap.sloRetry))}
          </div>
        </Section>
      )}
    </>
  );
}
