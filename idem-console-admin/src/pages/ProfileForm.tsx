import { useState } from 'react';
import { AUTH_LEVELS, CLIENT_AUTH_METHODS, PROTOCOL_TYPES, SUBJECT_SCHEMES, fromProfile, toProfile, validate, type ProfileForm as Form } from '../lib/profile';
import type { Profile } from '../lib/types';
import { Alert, Field } from '../ui';

interface Props {
  initial: Form;
  base: Profile;
  codeLocked: boolean;
  busy: boolean;
  onSubmit: (profile: Profile, reason: string) => void;
}

/** 온보딩·편집 폼. "JSON" 탭은 같은 문서를 직접 편집한다(스키마의 모든 키). */
export function ProfileForm({ initial, base, codeLocked, busy, onSubmit }: Props) {
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
  return (
    <div>
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
