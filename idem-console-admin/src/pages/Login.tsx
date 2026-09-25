import { useState, type FormEvent } from 'react';
import { post } from '../lib/api';
import type { LoginResponse, AdminMe } from '../lib/types';
import { useAuth } from '../auth';
import { ErrorBox, Field, Secret } from '../ui';

type Step = { kind: 'password' } | { kind: 'mfa'; token: string } | { kind: 'enroll'; token: string; secret: string; otpauthUri: string };

export function Login() {
  const { setMe } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [step, setStep] = useState<Step>({ kind: 'password' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submitPassword = async (e: FormEvent) => {
    e.preventDefault(); setBusy(true); setError(null);
    try {
      const r = await post<LoginResponse>('/auth/login', { username, password });
      if (r.status === 'OK' && r.admin) setMe(r.admin);
      else if (r.status === 'MFA_REQUIRED' && r.mfaToken) setStep({ kind: 'mfa', token: r.mfaToken });
      else if (r.status === 'MFA_ENROLL_REQUIRED' && r.mfaToken && r.secret) setStep({ kind: 'enroll', token: r.mfaToken, secret: r.secret, otpauthUri: r.otpauthUri ?? '' });
      else setError(new Error(`알 수 없는 응답: ${r.status}`));
    } catch (err) { setError(err); } finally { setBusy(false); }
  };

  const submitCode = async (e: FormEvent) => {
    e.preventDefault(); if (step.kind === 'password') return;
    setBusy(true); setError(null);
    try {
      const r = await post<{ status: string; admin: AdminMe }>('/auth/mfa', { mfaToken: step.token, code: code.trim() });
      setMe(r.admin);
    } catch (err) {
      setError(err);
      // 대기 토큰은 1회용 — 실패하면 처음부터
      setStep({ kind: 'password' }); setCode(''); setPassword('');
    } finally { setBusy(false); }
  };

  return (
    <main className="login">
      <section className="card">
        <h1 style={{ marginBottom: 12 }}>Idem 관리 콘솔</h1>
        <ErrorBox error={error} />
        {step.kind === 'password' && (
          <form onSubmit={submitPassword}>
            <Field label="사용자명"><input autoFocus autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required /></Field>
            <Field label="비밀번호"><input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required /></Field>
            <button className="btn" disabled={busy}>로그인</button>
          </form>
        )}
        {step.kind !== 'password' && (
          <form onSubmit={submitCode}>
            {step.kind === 'enroll' && (
              <>
                <p>첫 로그인입니다. 인증 앱(Google Authenticator·Microsoft Authenticator 등)에 아래 비밀을 등록하고 앱이 보여 주는 6자리 코드를 입력하세요.</p>
                <Secret label="2단계 비밀 (base32)" value={step.secret} note="지금만 표시됩니다 — 인증 앱에 등록하세요" />
                {step.otpauthUri && <p className="mono muted" style={{ wordBreak: 'break-all' }}>{step.otpauthUri}</p>}
              </>
            )}
            {step.kind === 'mfa' && <p>인증 앱의 6자리 코드를 입력하세요.</p>}
            <Field label="인증 코드"><input autoFocus inputMode="numeric" pattern="[0-9]{6}" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} required /></Field>
            <div className="row">
              <button className="btn" disabled={busy}>확인</button>
              <button type="button" className="btn secondary" onClick={() => { setStep({ kind: 'password' }); setCode(''); }}>처음으로</button>
            </div>
          </form>
        )}
      </section>
      <p className="muted" style={{ fontSize: 12 }}>세션은 유휴 15분·절대 8시간·동시 1개입니다. 5회 실패하면 15분 잠깁니다.</p>
    </main>
  );
}
