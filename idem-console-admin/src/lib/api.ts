// hub 관리 API 호출 — 같은 출처(/api/v1/admin, nginx·vite 프록시). 세션은 HttpOnly 쿠키, 모든 요청에 X-Requested-With(CSRF).
export const BASE = '/api/v1/admin';
export const CSRF_HEADER = 'X-Requested-With';
export const CSRF_VALUE = 'idem-console-admin';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;

  constructor(status: number, code: string, message: string, correlationId?: string) {
    super(message);
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }

  static from(status: number, body: unknown, raw: string): ApiError {
    if (body && typeof body === 'object') {
      const b = body as Record<string, unknown>;
      const code = typeof b.code === 'string' ? b.code : `HTTP-${status}`;
      const detail = typeof b.detail === 'string' ? b.detail : undefined;
      const message = typeof b.message === 'string' ? b.message : raw || `HTTP ${status}`;
      return new ApiError(status, code, detail && detail !== message ? `${message} — ${detail}` : message,
        typeof b.correlationId === 'string' ? b.correlationId : undefined);
    }
    return new ApiError(status, `HTTP-${status}`, raw || `HTTP ${status}`);
  }
}

let unauthorizedHandler: (() => void) | null = null;
/** 세션이 없거나 만료됐을 때(401, 로그인·2단계 요청 제외) 호출 — App 이 로그인 화면으로 보낸다 */
export function onUnauthorized(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

export async function api<T>(method: Method, path: string, body?: unknown, extraHeaders?: Record<string, string>): Promise<T> {
  const headers: Record<string, string> = { [CSRF_HEADER]: CSRF_VALUE, Accept: 'application/json', ...extraHeaders };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + path, {
    method,
    credentials: 'same-origin',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const raw = await res.text();
  let json: unknown = null;
  if (raw) {
    try { json = JSON.parse(raw); } catch { json = null; }
  }
  if (!res.ok) {
    if (res.status === 401 && !path.startsWith('/auth/login') && !path.startsWith('/auth/mfa')) unauthorizedHandler?.();
    throw ApiError.from(res.status, json, raw);
  }
  return (raw ? json : undefined) as T;
}

export const get = <T,>(path: string) => api<T>('GET', path);
export const post = <T,>(path: string, body?: unknown, headers?: Record<string, string>) => api<T>('POST', path, body, headers);
export const put = <T,>(path: string, body?: unknown, headers?: Record<string, string>) => api<T>('PUT', path, body, headers);

export function describe(e: unknown): string {
  if (e instanceof ApiError) return `${e.code}: ${e.message}`;
  if (e instanceof Error) return e.message;
  return String(e);
}
