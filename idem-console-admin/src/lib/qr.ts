// 1.1.1 G1-3 TOTP 등록 QR — otpauth URI 를 data: PNG 로. 브라우저 안에서만 만든다(비밀은 서버·네트워크로 다시 나가지 않는다).
import QRCode from 'qrcode';

export const OTPAUTH_PREFIX = 'otpauth://totp/';

/** otpauth://totp/ 로 시작하는 URI 만 받는다 — 그 밖의 값(빈 문자열·http 링크)은 null */
export function otpauthOrNull(uri: string | null | undefined): string | null {
  if (!uri) return null;
  const t = uri.trim();
  return t.startsWith(OTPAUTH_PREFIX) && t.length > OTPAUTH_PREFIX.length ? t : null;
}

/** 192px, 여백 1모듈, 오류 정정 M — 인증 앱이 화면에서 바로 읽을 크기 */
export async function otpauthQrDataUrl(uri: string | null | undefined): Promise<string | null> {
  const u = otpauthOrNull(uri);
  if (!u) return null;
  return QRCode.toDataURL(u, { width: 192, margin: 1, errorCorrectionLevel: 'M' });
}
