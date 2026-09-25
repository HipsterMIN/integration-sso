import { useState, type FormEvent } from 'react';
import { post } from '../lib/api';
import { useAuth } from '../auth';
import { navigate } from '../router';
import { Alert, ErrorBox, Field, Section } from '../ui';

export function Password({ forced = false }: { forced?: boolean }) {
  const { refresh, logout } = useAuth();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault(); setError(null);
    if (next !== confirm) { setError(new Error('새 비밀번호 확인이 다릅니다')); return; }
    setBusy(true);
    try {
      await post('/auth/password', { currentPassword: current, newPassword: next });
      setDone(true); setCurrent(''); setNext(''); setConfirm('');
      await refresh();
      if (forced) navigate('/services');
    } catch (err) { setError(err); } finally { setBusy(false); }
  };

  const body = (
    <form onSubmit={submit}>
      {forced && <Alert kind="warn">첫 로그인(또는 재설정) 뒤에는 비밀번호를 바꿔야 다른 화면을 쓸 수 있습니다.</Alert>}
      {done && <Alert kind="ok">비밀번호를 바꿨습니다.</Alert>}
      <ErrorBox error={error} />
      <Field label="현재 비밀번호"><input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required /></Field>
      <Field label="새 비밀번호" hint="10자 이상, 대/소문자·숫자·특수문자 중 3종, 사용자명 포함 금지, 최근 3개 재사용 금지"><input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} required minLength={10} /></Field>
      <Field label="새 비밀번호 확인"><input type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required /></Field>
      <div className="row">
        <button className="btn" disabled={busy}>변경</button>
        {forced && <button type="button" className="btn secondary" onClick={() => void logout()}>로그아웃</button>}
      </div>
    </form>
  );
  return forced ? <main className="login"><section className="card"><h1 style={{ marginBottom: 12 }}>비밀번호 변경</h1>{body}</section></main>
    : <Section title="비밀번호 변경">{body}</Section>;
}
