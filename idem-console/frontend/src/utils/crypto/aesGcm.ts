/**
 * AES-256-GCM 암호화 유틸리티
 *
 * Q-IM CI Token API v1.3 매뉴얼 §2.5 기준:
 * - 알고리즘: AES-256-GCM (IV 12바이트, GCM tag 128bit)
 * - 결과: base64(IV || ciphertext || tag)
 * - IV는 매 요청마다 SecureRandom 12바이트 신규 생성
 */

const AES_GCM_KEY = process.env.AES_GCM_KEY || '';

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
 * CI 평문을 AES-256-GCM으로 암호화한다.
 *
 * @param ciPlaintext CI 평문 문자열
 * @returns base64(IV(12B) || ciphertext || tag(16B))
 */
export async function encryptCi(ciPlaintext: string): Promise<string> {
	if (!AES_GCM_KEY) {
		throw new Error('AES_GCM_KEY 환경변수가 설정되지 않았습니다');
	}

	const keyBytes = base64ToBytes(AES_GCM_KEY);
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
