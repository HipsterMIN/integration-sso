/**
 * AES-256-GCM 암호화 유틸리티
 *
 * Q-IM CI Token API v1.3 매뉴얼 §2.5 기준:
 * - 알고리즘: AES-256-GCM (IV 12바이트, GCM tag 128bit)
 * - 결과: base64(IV || ciphertext || tag)
 * - IV는 매 요청마다 SecureRandom 12바이트 신규 생성
 *
 * B-1 보안 패치:
 *   - 기존: process.env.AES_GCM_KEY → FE 번들에 AES 키 노출
 *   - 변경: /api/v1/auth/provision/aes-gcm-key 엔드포인트에서 키 취득
 *           (ido 서버가 세션 인증 후 키 반환 — FE 번들 미포함)
 */
import { beApiInstance } from 'api/beInstance';

function base64ToBytes(b64: string): Uint8Array {
	const binary = atob(b64);
	const bytes = new Uint8Array(binary.length);
	for (let i = 0; i < binary.length; i += 1) {
		bytes[i] = binary.charCodeAt(i);
	}
	return bytes;
}

function bytesToBase64(bytes: Uint8Array): string {
	let binary = '';
	for (let i = 0; i < bytes.length; i += 1) {
		binary += String.fromCharCode(bytes[i]);
	}
	return btoa(binary);
}

/**
 * ido 서버에서 세션 바인딩된 AES-GCM 키(Base64)를 취득한다.
 * FE 번들에 키를 포함하지 않으므로 B-1 보안 요건을 충족한다.
 */
async function fetchAesGcmKey(): Promise<string> {
	const res = await beApiInstance.get<{ aesGcmKey: string }>(
		'/api/v1/auth/provision/aes-gcm-key',
	);
	const { aesGcmKey } = res.data;
	if (!aesGcmKey) {
		throw new Error('서버에서 AES-GCM 키를 수신하지 못했습니다');
	}
	return aesGcmKey;
}

/**
 * CI 평문을 AES-256-GCM으로 암호화한다.
 *
 * @param ciPlaintext CI 평문 문자열
 * @returns base64(IV(12B) || ciphertext || tag(16B))
 */
export async function encryptCi(ciPlaintext: string): Promise<string> {
	const aesGcmKeyB64 = await fetchAesGcmKey();

	const keyBytes = base64ToBytes(aesGcmKeyB64);
	const key = await crypto.subtle.importKey(
		'raw',
		keyBytes,
		{ name: 'AES-GCM' },
		false,
		['encrypt'],
	);

	const iv = crypto.getRandomValues(new Uint8Array(12));
	const encoder = new TextEncoder();
	const plainBytes = encoder.encode(ciPlaintext);

	// Web Crypto API: encrypt 결과에 GCM tag(16B)가 자동 포함됨
	const encrypted = await crypto.subtle.encrypt(
		{ name: 'AES-GCM', iv },
		key,
		plainBytes,
	);

	// IV(12B) || ciphertext+tag
	const result = new Uint8Array(iv.length + encrypted.byteLength);
	result.set(iv, 0);
	result.set(new Uint8Array(encrypted), iv.length);

	return bytesToBase64(result);
}
