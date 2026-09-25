import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, CSRF_HEADER, CSRF_VALUE, api, onUnauthorized } from '../src/lib/api';

function stub(status: number, body: string | null, headers: Record<string, string> = {}) {
  const fn = vi.fn(async () => new Response(body, { status, headers }));
  vi.stubGlobal('fetch', fn);
  return fn;
}

describe('api client', () => {
  afterEach(() => { vi.unstubAllGlobals(); onUnauthorized(null); });

  it('같은 출처·CSRF 헤더·JSON 본문으로 부른다', async () => {
    const fn = stub(200, '{"ok":true}', { 'Content-Type': 'application/json' });
    const r = await api<{ ok: boolean }>('POST', '/auth/login', { username: 'a', password: 'b' });
    expect(r).toEqual({ ok: true });
    const [url, init] = fn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/v1/admin/auth/login');
    expect(init.credentials).toBe('same-origin');
    expect((init.headers as Record<string, string>)[CSRF_HEADER]).toBe(CSRF_VALUE);
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(init.body).toBe('{"username":"a","password":"b"}');
  });

  it('204 는 undefined', async () => {
    stub(204, null);
    expect(await api('POST', '/auth/logout')).toBeUndefined();
  });

  it('오류는 ApiError(code·message·detail)', async () => {
    stub(400, '{"code":"E-IDO-135","message":"비밀번호 정책 위반","detail":"10자 이상"}');
    await expect(api('POST', '/auth/password', {})).rejects.toMatchObject({ status: 400, code: 'E-IDO-135', message: '비밀번호 정책 위반 — 10자 이상' });
    stub(502, 'bad gateway');
    const e = await api('GET', '/agencies').catch((x: unknown) => x) as ApiError;
    expect(e).toBeInstanceOf(ApiError);
    expect(e.code).toBe('HTTP-502');
  });

  it('401 이면 로그인·2단계 요청을 빼고 핸들러를 부른다', async () => {
    const h = vi.fn(); onUnauthorized(h);
    stub(401, '{"code":"E-IDO-130","message":"x"}');
    await expect(api('GET', '/agencies')).rejects.toBeInstanceOf(ApiError);
    expect(h).toHaveBeenCalledTimes(1);
    stub(401, '{"code":"E-IDO-132","message":"x"}');
    await expect(api('POST', '/auth/login', {})).rejects.toBeInstanceOf(ApiError);
    expect(h).toHaveBeenCalledTimes(1);
  });
});
