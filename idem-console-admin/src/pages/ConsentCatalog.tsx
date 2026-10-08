import { useCallback, useEffect, useState } from 'react';
import { get, post } from '../lib/api';
import { STATUS_LABEL, consentPath, emptyPublish, sortItems, toPublishBody, validatePublish } from '../lib/consent';
import type { PublishForm } from '../lib/consent';
import type { ConsentItem } from '../lib/types';
import { canWrite, useAuth } from '../auth';
import { Alert, ErrorBox, Field, Section, fmt } from '../ui';

/** 1.1 동의 카탈로그 카드 — 서비스 전용(serviceCode) 또는 플랫폼 공통(없음). 목록·새 버전 발행·종료. */
export function ConsentCatalog({ serviceCode, title }: { serviceCode?: string | null; title: string }) {
  const { me } = useAuth();
  const [items, setItems] = useState<ConsentItem[] | null>(null);
  const [includeInactive, setIncludeInactive] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [form, setForm] = useState<PublishForm>(emptyPublish);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [ok, setOk] = useState<string | null>(null);
  const path = consentPath(serviceCode);

  const load = useCallback(async () => {
    setError(null);
    try { setItems(sortItems(await get<ConsentItem[]>(`${path}?includeInactive=${includeInactive}`))); } catch (e) { setError(e); }
  }, [path, includeInactive]);
  useEffect(() => { void load(); }, [load]);

  const up = <K extends keyof PublishForm>(k: K, v: PublishForm[K]) => setForm((f) => ({ ...f, [k]: v }));
  const publish = async () => {
    const v = validatePublish(form);
    setFormErrors(v);
    if (v.length) return;
    setBusy(true); setError(null); setOk(null);
    try {
      const r = await post<ConsentItem>(path, toPublishBody(form));
      setOk(`발행했습니다: ${r.consentType} ${r.versionTag ?? ''} — 같은 유형의 이전 버전은 종료됐고 사용자는 다음 로그인에서 다시 동의합니다.`);
      setForm(emptyPublish());
      await load();
    } catch (e) { setError(e); } finally { setBusy(false); }
  };
  const retire = async (it: ConsentItem) => {
    if (!confirm(`${it.consentType} ${it.versionTag ?? ''} 버전을 종료합니다. 로그인 화면에서 더 묻지 않습니다. 계속할까요?`)) return;
    setBusy(true); setError(null); setOk(null);
    try { await post(`${path}/${encodeURIComponent(it.versionId)}/retire`); setOk(`종료했습니다: ${it.consentType}`); await load(); }
    catch (e) { setError(e); } finally { setBusy(false); }
  };

  return (
    <Section title={title} actions={<label><input type="checkbox" checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)} /> 종료된 버전 포함</label>}>
      <p className="muted">
        {serviceCode
          ? '이 서비스의 로그인에서만 묻는 항목입니다. 플랫폼 공통 항목(이용약관·개인정보 등)은 "동의 항목" 메뉴에서 관리합니다. 로그인 화면의 동의 단계는 프로파일 consent.enabled 로 켭니다(Handoff 유형; OIDC_RP 는 기관 RP 화면의 몫).'
          : '모든 서비스의 로그인에서 묻는 공통 항목입니다(프로파일 consent.includePlatform=false 인 서비스 제외). 새 버전을 발행하면 같은 유형의 이전 버전은 종료되고, 사용자는 다음 로그인에서 다시 동의합니다.'}
      </p>
      <ErrorBox error={error} />
      {ok && <Alert kind="ok">{ok}</Alert>}
      {items && (
        <table>
          <thead><tr><th>유형</th><th>버전</th><th>제목</th><th>필수</th><th>상태</th><th>시행</th><th>전문</th>{canWrite(me) && <th />}</tr></thead>
          <tbody>
            {items.map((it) => (
              <tr key={it.versionId}>
                <td className="mono">{it.consentType}</td>
                <td className="mono">{it.versionTag ?? '—'}</td>
                <td>{it.title ?? '—'}</td>
                <td>{it.required ? <span className="pill bad">필수</span> : <span className="pill">선택</span>}</td>
                <td><span className={`pill ${it.status === 'ACTIVE' ? 'ok' : ''}`}>{STATUS_LABEL[it.status] ?? it.status}</span></td>
                <td className="muted">{fmt(it.effectiveAt)}</td>
                <td>{it.contentUrl ? <a href={it.contentUrl} target="_blank" rel="noopener noreferrer">열기</a> : '—'}</td>
                {canWrite(me) && <td>{it.status === 'ACTIVE' && <button className="btn danger small" disabled={busy} onClick={() => void retire(it)}>종료</button>}</td>}
              </tr>
            ))}
            {items.length === 0 && <tr><td colSpan={8} className="muted">항목이 없습니다.</td></tr>}
          </tbody>
        </table>
      )}
      {canWrite(me) && (
        <>
          <h3>새 버전 발행</h3>
          {formErrors.length > 0 && <Alert kind="warn">{formErrors.join(' · ')}</Alert>}
          <div className="grid3">
            <Field label="유형" hint="예: TERMS_OF_SERVICE, PRIVACY_POLICY, MARKETING"><input value={form.consentType} onChange={(e) => up('consentType', e.target.value)} /></Field>
            <Field label="버전 태그" hint="예: 2026-10"><input value={form.versionTag} onChange={(e) => up('versionTag', e.target.value)} /></Field>
            <Field label="제목" hint="로그인 화면에 보이는 문구"><input value={form.title} onChange={(e) => up('title', e.target.value)} /></Field>
            <Field label="전문 URL" hint="로그인 화면의 '전문 보기' 링크 (http(s))"><input value={form.contentUrl} onChange={(e) => up('contentUrl', e.target.value)} /></Field>
            <Field label="시행 시각" hint="비우면 즉시"><input type="datetime-local" value={form.effectiveAt} onChange={(e) => up('effectiveAt', e.target.value)} /></Field>
          </div>
          <div className="row">
            <label><input type="checkbox" checked={form.required} onChange={(e) => up('required', e.target.checked)} /> 필수 (동의하지 않으면 로그인이 완료되지 않는다)</label>
            <button type="button" className="btn small" disabled={busy} onClick={() => void publish()}>발행</button>
          </div>
        </>
      )}
    </Section>
  );
}
