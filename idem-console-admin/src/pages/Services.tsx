import { useEffect, useState } from 'react';
import { PAGE_SIZE, agenciesQuery, pageCount } from '../lib/agencies';
import { get } from '../lib/api';
import type { AgencyPage } from '../lib/types';
import { canWrite, useAuth } from '../auth';
import { href, navigate } from '../router';
import { ErrorBox, Section, fmt } from '../ui';

/** 1.1.1 G1-3 — 서버 페이징·검색(GET /agencies?page&size&q). 한 페이지 50, 검색은 코드·이름 부분 일치(대소문자 무시) */
export function Services() {
  const { me } = useAuth();
  const [data, setData] = useState<AgencyPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [q, setQ] = useState('');
  const [term, setTerm] = useState('');
  const [page, setPage] = useState(0);

  // 입력 300ms 뒤에 검색어로 확정, 검색어가 바뀌면 1쪽으로
  useEffect(() => {
    const t = setTimeout(() => { setTerm(q); setPage(0); }, 300);
    return () => clearTimeout(t);
  }, [q]);

  useEffect(() => {
    let alive = true;
    setError(null);
    get<AgencyPage>(agenciesQuery(page, term)).then((d) => { if (alive) setData(d); }).catch((e) => { if (alive) setError(e); });
    return () => { alive = false; };
  }, [page, term]);

  const rows = data?.items ?? [];
  const pages = data ? pageCount(data.total, data.size || PAGE_SIZE) : 1;

  return (
    <Section title="기관(Service)" actions={canWrite(me) && <a className="btn" href={href('/services/new')}>새 기관 온보딩</a>}>
      <ErrorBox error={error} />
      <div className="row" style={{ marginBottom: 8 }}>
        <input placeholder="코드·이름 검색" value={q} onChange={(e) => setQ(e.target.value)} />
        <span className="muted">{data ? `${data.total}건` : '…'}</span>
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
          {data && rows.length === 0 && <tr><td colSpan={6} className="muted">{term ? `"${term}" 에 맞는 기관이 없습니다.` : '기관이 없습니다. "새 기관 온보딩" 으로 프로파일을 저장하면 만들어집니다.'}</td></tr>}
        </tbody>
      </table>
      {data && pages > 1 && (
        <div className="row" style={{ marginTop: 8 }}>
          <button type="button" className="btn secondary small" disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
          <span className="muted">{page + 1} / {pages}</span>
          <button type="button" className="btn secondary small" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>다음</button>
        </div>
      )}
    </Section>
  );
}
