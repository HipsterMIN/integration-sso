import type { ReactElement } from 'react';
import { isGlobalSystemAdmin, useAuth } from './auth';
import { href, useRoute } from './router';
import { Login } from './pages/Login';
import { Password } from './pages/Password';
import { Services } from './pages/Services';
import { ServiceDetail } from './pages/ServiceDetail';
import { Audit } from './pages/Audit';
import { Admins } from './pages/Admins';
import { Tenants } from './pages/Tenants';

export function App() {
  const { me, ready, logout } = useAuth();
  const route = useRoute();

  if (!ready) return <main className="muted">불러오는 중…</main>;
  if (!me) return <Login />;
  if (me.mustChangePassword) return <Password forced />;

  const [head, second] = route.parts;
  let page: ReactElement;
  if (head === 'services' && second) page = <ServiceDetail code={second} />;
  else if (head === 'audit') page = <Audit />;
  else if (head === 'admins' && isGlobalSystemAdmin(me)) page = <Admins />;
  else if (head === 'tenants') page = <Tenants />;
  else if (head === 'password') page = <Password />;
  else page = <Services />;

  const nav = (path: string, label: string) => (
    <a href={href(path)} className={route.parts[0] === path.slice(1) ? 'active' : ''}>{label}</a>
  );

  return (
    <>
      <header className="topbar">
        <h1>Idem 관리 콘솔</h1>
        <nav>
          {nav('/services', '기관')}
          {nav('/tenants', '테넌트')}
          {nav('/audit', '감사')}
          {isGlobalSystemAdmin(me) && nav('/admins', '관리자')}
        </nav>
        <span className="spacer" />
        <span className="who">{me.username} · {me.role}{me.tenantCode ? ` · ${me.tenantCode}` : ''} · <a href={href('/password')} style={{ color: '#fff' }}>비밀번호</a></span>
        <button type="button" className="btn small" onClick={() => void logout()}>로그아웃</button>
      </header>
      <main>{page}</main>
    </>
  );
}
