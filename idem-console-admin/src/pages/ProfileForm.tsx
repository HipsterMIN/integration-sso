import { useState } from 'react';
import { post } from '../lib/api';
import { draftText, pinDraft } from '../lib/ai';
import { AUTH_LEVELS, CLIENT_AUTH_METHODS, PROTOCOL_TYPES, SUBJECT_SCHEMES, fromProfile, toProfile, validate, type ProfileForm as Form } from '../lib/profile';
import type { AiDraft, Profile } from '../lib/types';
import { Alert, ErrorBox, Field } from '../ui';

interface Props {
  initial: Form;
  base: Profile;
  codeLocked: boolean;
  busy: boolean;
  onSubmit: (profile: Profile, reason: string) => void;
  /** 1.1 AI 운영 보조가 켜진 설치본이면 자연어 → 초안 카드를 보인다 (초안은 JSON 탭에 들어갈 뿐, 저장은 관리자의 저장 버튼) */
  aiEnabled?: boolean;
  /** 기존 기관이면 그 코드 — 초안의 service.code 를 이 값으로 고정한다 */
  lockedCode?: string | null;
}

/** 온보딩·편집 폼. "JSON" 탭은 같은 문서를 직접 편집한다(스키마의 모든 키). */
export function ProfileForm({ initial, base, codeLocked, busy, onSubmit, aiEnabled = false, lockedCode = null }: Props) {
  const [f, setF] = useState<Form>(initial);
  const [mode, setMode] = useState<'form' | 'json'>('form');
  const [json, setJson] = useState('');
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [problems, setProblems] = useState<string[]>([]);
  const up = <K extends keyof Form>(k: K, v: Form[K]) => setF((s) => ({ ...s, [k]: v }));

  const switchMode = (m: 'form' | 'json') => {
    if (m === 'json') { setJson(JSON.stringify(toProfile(f, base), null, 2)); setJsonError(null); }
    else if (mode === 'json') {
      try { setF(fromProfile(JSON.parse(json))); setJsonError(null); } catch (e) { setJsonError(String(e)); return; }
    }
    setMode(m);
  };

  const submit = () => {
    if (mode === 'json') {
      try { onSubmit(JSON.parse(json) as Profile, reason); setJsonError(null); } catch (e) { setJsonError(String(e)); }
      return;
    }
    const v = validate(f); setProblems(v);
    if (v.length === 0) onSubmit(toProfile(f, base), reason);
  };

  const oidc = f.type === 'OIDC_RP';
  /** AI 초안을 JSON 탭에 넣는다 — 폼 값은 JSON 탭에서 "폼" 으로 돌아올 때 다시 읽힌다 */
  const applyDraft = (draft: Profile) => {
    setJson(draftText(pinDraft(draft, lockedCode)));
    setJsonError(null);
    setMode('json');
  };
  const currentDoc = (): Profile => {
    if (mode === 'json') { try { return JSON.parse(json) as Profile; } catch { return toProfile(f, base); } }
    return toProfile(f, base);
  };
  return (
    <div>
      {aiEnabled && <AiDraftCard lockedCode={lockedCode} current={currentDoc} onApply={applyDraft} />}
      <div className="tabs">
        <button type="button" className={mode === 'form' ? 'active' : ''} onClick={() => switchMode('form')}>폼</button>
        <button type="button" className={mode === 'json' ? 'active' : ''} onClick={() => switchMode('json')}>JSON (전체 스키마)</button>
      </div>
      {problems.length > 0 && <Alert kind="error"><ul style={{ margin: 0, paddingLeft: 18 }}>{problems.map((p) => <li key={p}>{p}</li>)}</ul></Alert>}
      {jsonError && <Alert kind="error">{jsonError}</Alert>}

      {mode === 'json' ? (
        <textarea style={{ minHeight: 420, width: '100%' }} value={json} onChange={(e) => setJson(e.target.value)} spellCheck={false} />
      ) : (
        <>
          <h3>서비스</h3>
          <div className="grid3">
            <Field label="기관 코드" hint="영문·숫자·_·- 2~64자. 저장 뒤에는 바꿀 수 없다"><input value={f.code} onChange={(e) => up('code', e.target.value)} disabled={codeLocked} required /></Field>
            <Field label="이름"><input value={f.name} onChange={(e) => up('name', e.target.value)} required /></Field>
            <Field label="상태"><select value={f.status} onChange={(e) => up('status', e.target.value as Form['status'])}><option>ACTIVE</option><option>INACTIVE</option></select></Field>
            <Field label="테넌트" hint="비우면 DEFAULT"><input value={f.tenant} onChange={(e) => up('tenant', e.target.value)} /></Field>
          </div>

          <h3>연동 프로토콜</h3>
          <div className="grid3">
            <Field label="유형" hint="OIDC_RP: 표준 OIDC(Keycloak client 자동 프로비저닝). DIRECT 등: Handoff 티켓"><select value={f.type} onChange={(e) => up('type', e.target.value as Form['type'])}>{PROTOCOL_TYPES.map((t) => <option key={t}>{t}</option>)}</select></Field>
          </div>
          {oidc ? (
            <div className="grid2">
              <Field label="redirect URIs (줄마다 하나)" hint="와일드카드·fragment 금지"><textarea value={f.redirectUris} onChange={(e) => up('redirectUris', e.target.value)} /></Field>
              <Field label="post-logout redirect URIs (줄마다 하나)"><textarea value={f.postLogoutRedirectUris} onChange={(e) => up('postLogoutRedirectUris', e.target.value)} /></Field>
              <Field label="Back-Channel Logout URI"><input value={f.backchannelLogoutUri} onChange={(e) => up('backchannelLogoutUri', e.target.value)} /></Field>
              <Field label="client 인증 방식"><select value={f.clientAuthMethod} onChange={(e) => up('clientAuthMethod', e.target.value)}>{CLIENT_AUTH_METHODS.map((m) => <option key={m} value={m}>{m || '(기본 CLIENT_SECRET_BASIC)'}</option>)}</select></Field>
            </div>
          ) : (
            <div className="grid2">
              <Field label="콜백 허용 목록 (줄마다 하나)"><textarea value={f.callbackWhitelist} onChange={(e) => up('callbackWhitelist', e.target.value)} /></Field>
              <div>
                {f.type === 'BRIDGE' && <Field label="bridge 엔드포인트"><input value={f.bridge} onChange={(e) => up('bridge', e.target.value)} /></Field>}
                {f.type === 'APACHE_GATE' && <Field label="apacheGate 엔드포인트"><input value={f.apacheGate} onChange={(e) => up('apacheGate', e.target.value)} /></Field>}
                {f.type === 'INTERNAL_SSO' && <Field label="SSO 도메인"><input value={f.ssoDomain} onChange={(e) => up('ssoDomain', e.target.value)} /></Field>}
                <Field label="SSO 진입점(CAST)" hint="기관 간 SSO 로 들어올 때의 URL. 없으면 CAST 발급 거부(E-IDO-113)"><input value={f.ssoEntry} onChange={(e) => up('ssoEntry', e.target.value)} /></Field>
              </div>
            </div>
          )}

          <h3>식별자·속성</h3>
          <div className="grid2">
            <Field label="주체 식별 스킴" hint="비우면 기본(PAIRWISE_HMAC)"><select value={f.subjectScheme} onChange={(e) => up('subjectScheme', e.target.value)}>{SUBJECT_SCHEMES.map((s) => <option key={s} value={s}>{s || '(기본)'}</option>)}</select></Field>
            <Field label="전달 속성 (쉼표)" hint="예: name_masked, mobile_masked, birth_year"><input value={f.attributes} onChange={(e) => up('attributes', e.target.value)} /></Field>
          </div>

          <h3>정책</h3>
          <div className="grid3">
            <Field label="최소 인증수준"><select value={f.minAuthLevel} onChange={(e) => up('minAuthLevel', e.target.value as Form['minAuthLevel'])}>{AUTH_LEVELS.map((l) => <option key={l}>{l}</option>)}</select></Field>
            <Field label="허용 제공자 (쉼표)" hint="비우면 전부. 예: MOCK, NICE_PHONE"><input value={f.allowedProviders} onChange={(e) => up('allowedProviders', e.target.value)} /></Field>
            <div />
            <Field label="세션 유휴(분)"><input inputMode="numeric" value={f.idleMinutes} onChange={(e) => up('idleMinutes', e.target.value)} /></Field>
            <Field label="세션 절대(분)"><input inputMode="numeric" value={f.absoluteMinutes} onChange={(e) => up('absoluteMinutes', e.target.value)} /></Field>
            <Field label="동시 세션"><input inputMode="numeric" value={f.concurrent} onChange={(e) => up('concurrent', e.target.value)} /></Field>
          </div>
          <div className="row" style={{ marginBottom: 10 }}>
            <label><input type="checkbox" checked={f.assignmentRequired} onChange={(e) => up('assignmentRequired', e.target.checked)} /> 할당된 사용자만 허용 (assignment.required)</label>
            <label><input type="checkbox" checked={f.selfSignup} onChange={(e) => up('selfSignup', e.target.checked)} /> 셀프 가입 (assignment.selfSignup)</label>
          </div>
          <div className="row" style={{ marginBottom: 10 }}>
            <label><input type="checkbox" checked={f.consentEnabled} onChange={(e) => up('consentEnabled', e.target.checked)} /> 로그인 화면 동의 단계 (consent.enabled — Handoff 유형, 1.1)</label>
            <label><input type="checkbox" checked={f.consentIncludePlatform} disabled={!f.consentEnabled} onChange={(e) => up('consentIncludePlatform', e.target.checked)} /> 플랫폼 공통 항목 포함 (consent.includePlatform)</label>
          </div>

          <h3>한도</h3>
          <div className="grid3">
            <Field label="TPS"><input inputMode="numeric" value={f.tps} onChange={(e) => up('tps', e.target.value)} /></Field>
            <Field label="일 한도"><input inputMode="numeric" value={f.daily} onChange={(e) => up('daily', e.target.value)} /></Field>
          </div>
        </>
      )}

      <div className="row" style={{ marginTop: 12 }}>
        <input placeholder="변경 사유 (감사 기록, 선택)" value={reason} onChange={(e) => setReason(e.target.value)} style={{ flex: 1 }} />
        <button type="button" className="btn" disabled={busy} onClick={submit}>저장</button>
      </div>
    </div>
  );
}

/** 1.1 AI 운영 보조 — 자연어 요청 → 프로파일 초안. 서버가 스키마 검증 결과를 함께 주며 저장하지 않는다. */
function AiDraftCard({ lockedCode, current, onApply }: { lockedCode: string | null; current: () => Profile; onApply: (draft: Profile) => void }) {
  const [prompt, setPrompt] = useState('');
  const [useCurrent, setUseCurrent] = useState(!!lockedCode);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<AiDraft | null>(null);
  const run = async () => {
    if (!prompt.trim()) return;
    setBusy(true); setError(null); setResult(null);
    try {
      setResult(await post<AiDraft>('/ai/profile-draft', { prompt: prompt.trim(), serviceCode: lockedCode ?? undefined, base: useCurrent ? current() : undefined }));
    } catch (e) { setError(e); } finally { setBusy(false); }
  };
  return (
    <div className="card" style={{ background: '#f8fafc' }}>
      <div className="card-head"><h2>AI 초안 (선택)</h2><span className="muted">초안은 JSON 탭에 들어갈 뿐입니다 — 검토 후 저장 버튼을 눌러야 반영됩니다</span></div>
      <Field label="요청 (자연어)" hint="예: 표준 OIDC 로 붙는 세무 민원 포털, redirect https://tax.example.org/cb, L2 이상, 할당된 사용자만">
        <textarea value={prompt} onChange={(e) => setPrompt(e.target.value)} maxLength={4000} style={{ minHeight: 60 }} />
      </Field>
      <div className="row">
        <button type="button" className="btn secondary small" disabled={busy || !prompt.trim()} onClick={() => void run()}>{busy ? '만드는 중…' : '초안 만들기'}</button>
        <label><input type="checkbox" checked={useCurrent} onChange={(e) => setUseCurrent(e.target.checked)} /> 현재 문서를 출발점으로</label>
      </div>
      <ErrorBox error={error} />
      {result && (
        <>
          {result.violations.length === 0
            ? <Alert kind="ok">{result.note} (모델 {result.model})</Alert>
            : <Alert kind="warn"><div>{result.note} (모델 {result.model})</div><ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>{result.violations.map((v) => <li key={v}>{v}</li>)}</ul></Alert>}
          <pre className="mono" style={{ maxHeight: 260, overflow: 'auto', background: '#fff', border: '1px solid var(--line)', padding: 8 }}>{draftText(result.draft)}</pre>
          <button type="button" className="btn small" onClick={() => onApply(result.draft)}>JSON 탭에 넣기</button>
        </>
      )}
    </div>
  );
}

