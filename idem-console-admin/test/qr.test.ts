import { describe, expect, it } from 'vitest';
import { otpauthOrNull, otpauthQrDataUrl } from '../src/lib/qr';

describe('TOTP 등록 QR (1.1.1 G1-3)', () => {
  const uri = 'otpauth://totp/Idem%20Admin:admin?secret=JBSWY3DPEHPK3PXP&issuer=Idem%20Admin&digits=6&period=30';

  it('otpauth://totp/ 로 시작하는 URI 만 받는다', () => {
    expect(otpauthOrNull(uri)).toBe(uri);
    expect(otpauthOrNull(`  ${uri}  `)).toBe(uri);
    expect(otpauthOrNull('')).toBeNull();
    expect(otpauthOrNull(null)).toBeNull();
    expect(otpauthOrNull(undefined)).toBeNull();
    expect(otpauthOrNull('https://example.org/otpauth://totp/x')).toBeNull();
    expect(otpauthOrNull('otpauth://totp/')).toBeNull();
    expect(otpauthOrNull('otpauth://hotp/x?secret=A')).toBeNull();
  });

  it('data:image/png PNG 를 돌려주고, 받지 않는 값은 null', async () => {
    const d = await otpauthQrDataUrl(uri);
    expect(d).toMatch(/^data:image\/png;base64,/);
    expect(d!.length).toBeGreaterThan(500);
    await expect(otpauthQrDataUrl('')).resolves.toBeNull();
    await expect(otpauthQrDataUrl('http://x')).resolves.toBeNull();
  });
});
