import { useEffect, useState } from 'react';
import { get } from '../lib/api';
import type { Agency } from '../lib/types';
import { canWrite, useAuth } from '../auth';
import { href, navigate } from '../router';
import { ErrorBox, Section, fmt } from '../ui';

export function Services() {
  const { me } = useAuth();
  const [list, setList] = useState<Agency[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [q, setQ] = useState('');

  useEffect(() => {
    get<Agency[]>('/agencies?page=0&size=500').then(setList).catch(setError);
  }, []);

  const rows = (list ?? []).filter((a) => !q || a.agencyCode.toLowerCase().includes(q.toLowerCase()) || (a.officialName ?? '').toLowerCase().includes(q.toLowerCase()));

  return (
    <Section title="기관(Service)" actions={canWrite(me) && <a className="btn" href={href('/services/new')}>새 기관 온보딩</a>}>
      <ErrorBox error={error} />
      <div className="row" style={{ marginBottom: 8 }}>
        <input placeholder="코드·이름 검색" value={q} onChange={(e) => setQ(e.target.value)} />
        <span className="muted">{list ? `${rows.length}/${list.length}` : '…'}</span>
      </div>
      <table>
        <thead><tr><th>코드</th><th>이름</th><th>연동</th><th>인증수준</th><th>상태</th><th>수정</th></tr></thead>
        <tbody>
          {rows.map((a) => (
            <tr key={a.agencyCode} className="click" onClick={() => navigate(`/services/${encodeURIComponent(a.agencyCode)}`)}>
              <td className="mono">{a.agencyCode}</td>
              <td>{a.officialName}</td>
              <td><span className="pill">{a.integrationType ?? '—'}</span></td>
              <td>{a.minAuthLevel ?? '—'}</td>
              <td><span className={`pill ${a.active ? 'ok' : 'bad'}`}>{a.active ? 'ACTIVE' : 'INACTIVE'}</span></td>
              <td className="muted">{fmt(a.updatedAt)}</td>
            </tr>
          ))}
          {list && rows.length === 0 && <tr><td colSpan={6} className="muted">기관이 없습니다. "새 기관 온보딩" 으로 프로파일을 저장하면 만들어집니다.</td></tr>}
        </tbody>
      </table>
    </Section>
  );
}
