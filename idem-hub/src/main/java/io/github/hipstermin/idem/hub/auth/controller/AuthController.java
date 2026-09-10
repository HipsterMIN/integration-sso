package io.github.hipstermin.idem.hub.auth.controller;

import io.github.hipstermin.idem.common.util.SecurePasswordGenerator;
import io.github.hipstermin.idem.hub.auth.dto.*;
import io.github.hipstermin.idem.hub.auth.service.AuthService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 본인인증 API 컨트롤러
 *
 * <p>본인인증은 {@code /api/v1/auth/providers/{code}/…}(SPI) 로 제공한다. 벤더 경로(NICE/OACX)는
 * {@link io.github.hipstermin.idem.hub.auth.legacy.LegacyVendorAuthController} 가 1 릴리스 동안 프록시한다 (S5a).
 * onepass-fe React SPA의 BFF(Backend-For-Frontend)로 동작.
 *
 * <h2>보안 정책</h2>
 * <ul>
 *   <li>Q1=B: API Key 검증 없음 — Nginx same-origin 프록시로 보안 처리</li>
 *   <li>Q3=B: CI(연계정보)는 FE에 미반환 — 백엔드 내부에서만 처리 (PII 보호)</li>
 *   <li>CORS: {@code /api/v1/auth/**} 매핑 추가 ({@code IdoWebMvcConfig})</li>
 * </ul>
 *
 * <h2>엔드포인트 목록</h2>
 * <pre>
 * POST /api/v1/auth/nice/ci-check        — NICE 인증 CI 기반 회원 확인 (조회 전용)
 * POST /api/v1/auth/callback             — 기업 간편인증 콜백 (Q2=B, 향후 FE 연동)
 * GET  /api/v1/auth/provision/temp-password — 임시 비밀번호 생성 (CSPRNG 기반)
 * GET  /api/v1/auth/provision/aes-gcm-key  — FE AES-GCM 키 제공 (B-1: FE 번들 미포함)
 * POST /api/v1/auth/ci-token              — CI → ciToken 교환 (Q3=B: CI FE 미반환)
 * </pre>
 *
 * <h2>FE 연동 방법</h2>
 * <p>FE는 {@code beApiInstance} (axios)를 사용하여 이 API들을 호출한다.
 * {@code beApiInstance}는 {@code /api} 경로로 요청을 보내며, webpack dev-server proxy가
 * {@code http://localhost:8083}(ido)으로 전달한다.
 *
 * <pre>
 * // FE (useNicePhoneAuth.ts)
 * const response = await beApiInstance.get('/api/v1/auth/nice/phone/url', {
 *   params: { returnUrl: window.location.href }
 * });
 * </pre>
 *
 * @see AuthService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;

    // ─────────────────────────────────────────────────────────────────────────
    // KR 회원 조회 (CI 기반 — S8 에서 KR 에디션으로)
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/nice/ci-check")
    public CiCheckResponse niceCiCheck(@Valid @RequestBody CiCheckRequest request) {
        return authService.checkNiceCi(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 기업인증 API (Q2=B — 향후 FE 기업인증 구현 대비)
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // 프로비저닝 보조 API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 암호학적으로 안전한 임시 비밀번호 생성 (CSPRNG 기반)
     *
     * <p>{@code GET /api/v1/auth/provision/temp-password}
     *
     * <p>기업 관리자 계정 초기화 시 임시 비밀번호를 서버 사이드에서 생성하여 반환한다.
     * {@link SecurePasswordGenerator}를 사용하여 {@link java.security.SecureRandom} 기반
     * CSPRNG(Cryptographically Secure PRNG)으로 생성하므로 {@code Math.random()} 대비
     * 예측 불가능한 비밀번호를 보장한다.
     *
     * <p><b>보안 배경:</b>
     * FE Step5에서 {@code Math.random().toString(36)}으로 임시 비밀번호를 생성하는 것은
     * 암호학적으로 안전하지 않다(예측 가능한 PRNG). 68개 기관 관리자 계정의 초기 비밀번호에
     * 이 취약점이 적용되면 계정 탈취 위험이 있다.
     * 이 엔드포인트를 통해 FE가 서버 사이드 생성 비밀번호를 사용하도록 마이그레이션해야 한다.
     *
     * <p><b>비밀번호 정책:</b>
     * 대문자·소문자·숫자·특수문자 각 2개 이상 포함, 기본 12자.
     * Keycloak 허용 특수문자(!@#$%^&amp;*) 범위 내 생성.
     *
     * <p><b>운영 권고:</b>
     * 이 API가 반환한 비밀번호는 임시 비밀번호이며, Keycloak 사용자 생성 시
     * {@code requiredAction: UPDATE_PASSWORD}를 반드시 설정하여
     * 첫 로그인 후 사용자가 직접 변경하도록 강제해야 한다.
     *
     * <p><b>FE 마이그레이션 가이드:</b>
     * <pre>
     * // Step5.tsx — 기존 (비안전 PRNG)
     * const password = `Rnd${Math.random().toString(36).slice(2, 10)}!${Math.floor(Math.random() * 90 + 10)}`;
     *
     * // Step5.tsx — 개선 (서버 사이드 CSPRNG)
     * const { data } = await beApiInstance.get('/api/v1/auth/provision/temp-password');
     * const password = data.password;
     * </pre>
     *
     * <p><b>응답 예시:</b>
     * <pre>
     * { "password": "aB3#Kp9!mZ2@" }
     * </pre>
     *
     * @return 임시 비밀번호 응답 (password 필드)
     */
    @GetMapping("/provision/temp-password")
    public Map<String, String> generateTempPassword() {
        String password = SecurePasswordGenerator.generate();
        log.info("[임시비밀번호] CSPRNG 기반 임시 비밀번호 생성 완료");
        return Map.of("tempPassword", password);
    }

    /**
     * FE AES-GCM 키 제공 (B-1)
     *
     * <p>{@code GET /api/v1/auth/provision/aes-gcm-key}
     *
     * <p>FE 번들({@code AES_GCM_KEY} webpack DefinePlugin)에 AES-GCM 키를 포함하지 않고
     * 서버 사이드에서 세션 바인딩 키를 제공한다.
     * FE는 앱 초기화 시 이 엔드포인트를 호출하여 런타임에 키를 주입받아야 한다.
     *
     * <p><b>보안 원칙 (B-1):</b>
     * FE 번들에 AES-GCM 키를 포함하면 번들 분석으로 키가 노출된다.
     * 이 엔드포인트를 통해 키를 런타임에 주입하면 번들 분석으로부터 키를 보호할 수 있다.
     *
     * <p><b>운영 필수 설정:</b>
     * {@code FE_AES_GCM_KEY} 환경변수 미설정 시 500 오류 반환.
     *
     * <p><b>응답 예시:</b>
     * <pre>
     * { "aesGcmKey": "base64EncodedKey=" }
     * </pre>
     *
     * <p><b>응답 코드:</b>
     * <ul>
     *   <li>{@code 200} — 성공 (aesGcmKey 포함)</li>
     *   <li>{@code 500} — 서버 설정 오류 (FE_AES_GCM_KEY 미설정)</li>
     * </ul>
     *
     * @return FE AES-GCM 키 응답 (aesGcmKey 필드)
     */
    @GetMapping("/provision/aes-gcm-key")
    public Map<String, String> getFeAesGcmKey() {
        return authService.getFeAesGcmKey();
    }

    /**
     * CI → ciToken 교환 엔드포인트 (Q3=B)
     *
     * <p>{@code POST /api/v1/auth/ci-token}
     *
     * <p>FE가 AES-GCM으로 암호화하여 전달한 CI를 ido BE가 복호화하고,
     * Q-IM 공유키로 재암호화하여 Q-IM에 등록 후 ciToken을 발급받아 반환한다.
     *
     * <p><b>보안 설계 (Q3=B)</b>:
     * CI는 FE에서 AES-GCM 암호화 후 ido BE로만 전달된다.
     * ido BE는 CI 평문을 Q-IM에만 전달하고, FE 응답에는 ciToken(불투명 식별자)만 반환한다.
     * CI 원문은 네트워크 상에서 평문으로 전달되지 않는다.
     *
     * <p><b>FE 마이그레이션 가이드</b>:
     * 기존 {@code extInstance}를 통한 Q-IM 직접 호출 방식에서 이 엔드포인트로 전환한다.
     * <pre>
     * // 기존 (ciToken.ts — Q-IM 직접 호출, 보안 위반)
     * const { data } = await extInstance.post('/api/v1/users/ci-token', { encCi });
     *
     * // 변경 후 (beApiInstance — ido 경유, 보안 준수)
     * const encryptedCi = await encryptAesGcm(rawCi);
     * const { data } = await beApiInstance.post('/api/v1/auth/ci-token', {
     *   encryptedCi,
     *   mbrDvsnCd: 'A101'
     * });
     * const ciToken = data.ciToken;
     * </pre>
     *
     * <p><b>응답 코드:</b>
     * <ul>
     *   <li>{@code 2000} — 성공 (ciToken 포함)</li>
     *   <li>{@code 4000} — 파라미터 오류 (encryptedCi 누락, mbrDvsnCd 잘못됨)</li>
     *   <li>{@code 4010} — CI 복호화 실패 (AES-GCM 키 불일치)</li>
     *   <li>{@code 5010} — Q-IM 연동 실패</li>
     *   <li>{@code 5000} — 서버 설정 오류 (FE_AES_GCM_KEY 미설정)</li>
     * </ul>
     *
     * @param request FE 요청 (encryptedCi, mbrDvsnCd, bizno)
     * @return ciToken 교환 결과
     */
    @PostMapping("/ci-token")
    public CiTokenExchangeResponse exchangeCiToken(
            @Valid @RequestBody CiTokenExchangeRequest request) {
        return authService.exchangeCiToken(request);
    }

    /**
     * 기업 간편인증 콜백 수신 처리 (Q2=B)
     *
     * <p>{@code POST /api/v1/auth/callback}
     *
     * <p>간편인증창(통합인증 서버)이 FE에 postMessage로 전달한 콜백 데이터를
     * 수신하여 통합인증 서버에 auth-check를 요청한다.
     *
     * <p><b>현재 상태 (Q2=B):</b>
     * onepass-fe FE Step3(기업인증) 코드에 "미구현" 주석 다수 존재.
     * 현재 FE에서 이 API를 호출하지 않음.
     * 향후 FE 기업인증 구현 시 사용 예정.
     *
     * <p><b>FE 향후 사용 예시:</b>
     * <pre>
     * // 기업인증 팝업 완료 후 (향후 FE 구현 시)
     * window.addEventListener('message', async (event) => {
     *   // event.data = { txId, tokenId, siteInfo: { siteId }, userToken, hubToken }
     *   const { data } = await beApiInstance.post('/api/v1/auth/callback', event.data);
     *   if (data.resultCode === '2000') {
     *     const { name, businessNumber, birth } = data.resultData;
     *   }
     * });
     * </pre>
     *
     * <p><b>응답 코드:</b>
     * <ul>
     *   <li>{@code 2000} — 성공 (resultData: name, businessNumber, birth, phone, bizOpendt)</li>
     *   <li>{@code 5000} — 내부 처리 오류</li>
     *   <li>{@code 5001} — 통합인증 서버 응답 없음</li>
     * </ul>
     *
     * @param request 간편인증 콜백 요청 (siteInfo, txId, tokenId, userToken, hubToken)
     * @return 기업인증 결과 응답 (resultCode, resultMsg, resultData)
     */
    @PostMapping("/callback")
    public AuthCallbackResponse callback(@RequestBody AuthCallbackRequest request) {
        return authService.callback(request);
    }
}
