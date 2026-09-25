import { useState, type ReactNode } from 'react';
import { describe } from './lib/api';

export function Alert({ kind = 'info', children }: { kind?: 'info' | 'error' | 'ok' | 'warn'; children: ReactNode }) {
  return <div className={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>{children}</div>;
}

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null;
  return <Alert kind="error">{describe(error)}</Alert>;
}

/** 한 번만 보이는 비밀(API 키·client secret·임시 비밀번호) */
export function Secret({ label, value, note }: { label: string; value: string; note?: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try { await navigator.clipboard.writeText(value); setCopied(true); } catch { setCopied(false); }
  };
  return (
    <div className="secret" role="status">
      <div className="secret-label">{label} <small>{note ?? '지금만 표시됩니다 — Idem 은 저장하지 않습니다'}</small></div>
      <code className="secret-value">{value}</code>
      <button type="button" className="btn small" onClick={copy}>{copied ? '복사됨' : '복사'}</button>
    </div>
  );
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function Section({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="card">
      <header className="card-head"><h2>{title}</h2><div>{actions}</div></header>
      {children}
    </section>
  );
}

export const fmt = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleString() : '—');
