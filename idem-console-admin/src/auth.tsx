import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiError, get, onUnauthorized, post } from './lib/api';
import type { AdminMe } from './lib/types';

interface AuthState {
  me: AdminMe | null;
  ready: boolean;
  setMe: (me: AdminMe | null) => void;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
}

const Ctx = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<AdminMe | null>(null);
  const [ready, setReady] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setMe(await get<AdminMe>('/auth/me'));
    } catch (e) {
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) setMe(null);
      else throw e;
    } finally {
      setReady(true);
    }
  }, []);

  const logout = useCallback(async () => {
    try { await post('/auth/logout'); } catch { /* 이미 끝난 세션이어도 화면은 로그아웃한다 */ }
    setMe(null);
  }, []);

  useEffect(() => {
    onUnauthorized(() => setMe(null));
    void refresh();
    return () => onUnauthorized(null);
  }, [refresh]);

  const value = useMemo(() => ({ me, ready, setMe, refresh, logout }), [me, ready, refresh, logout]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthState {
  const v = useContext(Ctx);
  if (!v) throw new Error('AuthProvider 밖');
  return v;
}

/** 전역 SYSTEM_ADMIN — 관리자 관리·테넌트 쓰기 가능 */
export const isGlobalSystemAdmin = (me: AdminMe | null) => !!me && me.role === 'SYSTEM_ADMIN' && !me.tenantCode;
export const canWrite = (me: AdminMe | null) => !!me && me.role !== 'AUDITOR';
