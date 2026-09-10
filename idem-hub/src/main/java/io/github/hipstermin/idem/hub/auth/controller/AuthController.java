package io.github.hipstermin.idem.hub.auth.controller;

import io.github.hipstermin.idem.common.util.SecurePasswordGenerator;
import io.github.hipstermin.idem.hub.auth.dto.*;
import io.github.hipstermin.idem.hub.auth.service.AuthService;
import io.github.hipstermin.idem.hub.auth.service.NiceAuthService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 본인인증 API 컨트롤러
 *
 * <p>NICE 휴대폰 본인인증 및 OACX 전자서명 간편서명 API를 제공한다.
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
 * GET  /api/v1/auth/nice/phone/url        — NICE 휴대폰 인증 URL 발급
 * POST /api/v1/auth/nice/phone/result     — NICE 휴대폰 인증 결과 조회
 * POST /api/v1/auth/nice/ci-check        — NICE 인증 CI 기반 회원 확인 (조회 전용)
 * POST /api/v1/auth/oacx/access-info     — OACX 접근키/토큰 발급
 * POST /api/v1/auth/oacx/easysign        — OACX 간편서명 결과 처리
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
 * @see NiceAuthService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;
    private final NiceAuthService niceAuthService;

    // ─────────────────────────────────────────────────────────────────────────
    // NICE 휴대폰 본인인증 API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * NICE 휴대폰 본인인증 URL 발급
     *
     * <p>{@code GET /api/v1/auth/nice/phone/url?returnUrl=...}
     *
     * <p>NICE IDO 통합인증 표준창 URL을 발급한다.
     * 내부적으로 Access Token 발급(Redis 캐시) 후 NICE URL 발급 API를 호출.
     * 응답의 authUrl로 팝업을 열고 완료 후 requestNo를 이용해 결과 조회.
     *
     * <p><b>FE 사용 예시:</b>
     * <pre>
     * // useNicePhoneAuth.ts
     * const { data } = await beApiInstance.get('/api/v1/auth/nice/phone/url', {
     *   params: { returnUrl: 'https://portal.example.org/otp/auth-result' }
     * });
     * if (data.resultCode === '2000') {
     *   // data.authUrl: NICE 표준창 URL
     *   // data.requestNo: 결과 조회 시 필수 (보관 필요)
     *   const popup = window.open(data.authUrl, 'niceAuth', 'width=500,height=600');
     * }
     * </pre>
     *
     * <p><b>응답 코드:</b>
     * <ul>
     *   <li>{@code 2000} — 성공 (authUrl, requestNo 포함)</li>
     *   <li>{@code 5001} — NICE URL 발급 실패</li>
     *   <li>{@code 5000} — 내부 오류</li>
     * </ul>
     *
     * @param returnUrl 인증 완료 후 리다이렉트 URL (선택, 미입력 시 설정 기본값 사용)
     * @return NICE 인증 URL 응답 (resultCode, resultMsg, authUrl, requestNo)
     */
    @GetMapping("/nice/phone/url")
    public NicePhoneAuthUrlResponse getNicePhoneAuthUrl(
            @RequestParam(value = "returnUrl", required = false) String returnUrl) {
        return niceAuthService.getNicePhoneAuthUrl(returnUrl);
    }

    /**
     * NICE 휴대폰 본인인증 결과 조회
     *
     * <p>{@code POST /api/v1/auth/nice/phone/result}
     *
     * <p>NICE 팝업 완료 후 postMessage로 받은 web_transaction_id와
     * URL 발급 시 받은 request_no로 인증 결과를 조회하고 복호화한다.
     *
     * <p><b>보안 정책 (Q3=B):</b>
     * 복호화 결과에 CI가 포함되어 있어도 FE에 반환하지 않음. DI는 반환.
     *
     * <p><b>FE 사용 예시:</b>
     * <pre>
     * // useNicePhoneAuth.ts
     * window.addEventListener('message', async (event) => {
     *   const { web_transaction_id, request_no } = event.data;
     *   const { data } = await beApiInstance.post('/api/v1/auth/nice/phone/result', {
     *     web_transaction_id,
     *     request_no
     *   });
     *   if (data.resultCode === '2000') {
     *     // data.resultData: { name, birthdate, gender, nationalInfo, di, mobileCo, mobileNo }
     *     // CI는 응답에 없음 (보안 정책 Q3=B)
     *   }
     * });
     * </pre>
     *
     * <p><b>응답 코드:</b>
     * <ul>
     *   <li>{@code 2000} — 성공 (resultData 포함)</li>
     *   <li>{@code 4000} — 파라미터 오류 (request_no 누락, 세션 없음/만료)</li>
     *   <li>{@code 5002} — NICE 결과 조회 실패</li>
     *   <li>{@code 5003} — 무결성 검증 실패</li>
     *   <li>{@code 5000} — 내부 오류</li>
     * </ul>
     *
     * @param request web_transaction_id, request_no 포함 요청 body
     * @return NICE 인증 결과 (name, birthdate, gender, nationalInfo, di, mobileCo, mobileNo)
     */
    @PostMapping("/nice/phone/result")
    public NicePhoneAuthResultResponse getNicePhoneAuthResult(
            @Valid @RequestBody NicePhoneAuthResultRequest request) {
        return niceAuthService.getNicePhoneAuthResult(request);
    }

    /**
     * NICE 본인인증 CI 기반 회원 확인
     *
     * <p>{@code POST /api/v1/auth/nice/ci-check}
     *
     * <p>NICE 휴대폰 인증 결과에서 획득한 CI로 기존 회원 조회 또는 신규 등록 처리.
     * CI는 요청 body에 포함되며, 서버 내부에서만 처리하고 응답에는 CI를 포함하지 않음.
     *
     * <p><b>주의:</b> 현재 IM API 미연동으로 파라미터 검증 후 성공 반환만 구현됨.
     * S7-T6에서 IM API 연동 후 실제 회원 조회/등록 로직 구현 예정.
     *
     * <p><b>FE 요청 예시 (개인회원):</b>
     * <pre>
     * // nice/ciCheck.ts
     * const { data } = await beApiInstance.post('/api/v1/auth/nice/ci-check', {
     *   ci: niceResult.ci,     // NICE 결과에서 수신한 CI
     *   mbrDvsnCd: 'A101',
     *   indvlMbrNm: '홍길동',
     *   indvlMbrId: 'user123'
     * });
     * </pre>
     *
     * <p><b>FE 요청 예시 (기업회원):</b>
     * <pre>
     * const { data } = await beApiInstance.post('/api/v1/auth/nice/ci-check', {
     *   ci: niceResult.ci,
     *   mbrDvsnCd: 'A102',
     *   cmpMbrId: 'company123',
     *   bizno: '1234567890'
     * });
     * </pre>
     *
     * <p><b>응답 코드:</b>
     * <ul>
     *   <li>{@code 2000} — 성공 (result: true)</li>
     *   <li>{@code 4000} — 파라미터 오류 (ci 누락, mbrDvsnCd 잘못됨, bizno 누락)</li>
     * </ul>
     *
     * @param request CI 확인 요청 (ci, mbrDvsnCd, bizno 등)
     * @return CI 확인 결과 (resultCode, result, indvlMbrId/cmpMbrId)
     */
    @PostMapping("/nice/ci-check")
    public CiCheckResponse niceCiCheck(@Valid @RequestBody CiCheckRequest request) {
        return authService.checkNiceCi(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OACX 전자서명 간편서명 API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * OACX 전자서명 접근키/토큰 발급
     *
     * <p>{@code POST /api/v1/auth/oacx/access-info}
     *
     * <p>OACX JS SDK 초기화에 필요한 접근키(accKey)와 접근토큰(accToken)을 발급한다.
     *
     * <p><b>OACX 인증 플로우 (Step 1~3):</b>
     * <pre>
     * Step 1: FE → POST /api/v1/auth/oacx/access-info (body: "simpleAuth")
     * Step 2: ido → OACX SDK.getAccessInfo() 호출
     * Step 3: FE ← {fn, accKey, accToken}
     * Step 4: FE → OACXsdk.init({fn, accKey, accToken}) → 간편서명 팝업 실행
     * </pre>
     *
     * <p><b>Content-Type 주의:</b>
     * Request Body는 JSON string ({@code "simpleAuth"})이므로
     * axios 호출 시 Content-Type: application/json 확인 필요.
     *
     * <p><b>FE 사용 예시:</b>
     * <pre>
     * // usePersonalEasyAuth.ts
     * const { data } = await beApiInstance.post(
     *   '/api/v1/auth/oacx/access-info',
     *   JSON.stringify('simpleAuth'),  // plain string을 JSON으로 전달
     *   { headers: { 'Content-Type': 'application/json' } }
     * );
     * if (data.resultCode === '2000') {
     *   OACXsdk.init({ fn: data.fn, accKey: data.accKey, accToken: data.accToken });
     * }
     * </pre>
     *
     * <p><b>응답 코드:</b>
     * <ul>
     *   <li>{@code 2000} — 성공 (fn, accKey, accToken 포함)</li>
     *   <li>{@code 5001} — OACX 접근정보 발급 실패</li>
     * </ul>
     *
     * @param fn OACX 기능 코드 (plain JSON string, 예: {@code "simpleAuth"})
     * @return OACX 접근 정보 응답 (fn, accKey, accToken)
     */
    @PostMapping("/oacx/access-info")
    public OacxAccessInfoResponse getOacxAccessInfo(@RequestBody String fn) {
        return authService.getOacxAccessInfo(fn);
    }

    /**
     * OACX 전자서명 간편서명 결과 처리
     *
     * <p>{@code POST /api/v1/auth/oacx/easysign}
     *
     * <p>OACX JS SDK 간편서명 완료 콜백 데이터를 수신하여 JWT를 복호화하고
     * 사용자 정보를 반환한다.
     *
     * <p><b>보안 정책 (Q3=B):</b>
     * CI(연계정보)는 복호화 결과에 있어도 FE에 반환하지 않음.
     * {@code OacxEasysignResponse}의 ci 필드가 null이며 {@code @JsonInclude(NON_NULL)}로 응답에서 제외.
     *
     * <p><b>OACX 인증 플로우 (Step 7~11):</b>
     * <pre>
     * Step 5: FE → OACXsdk.open() → 간편서명 팝업 실행
     * Step 6: 사용자 간편서명 완료
     * Step 7: OACX SDK → FE 콜백 (fn, status, res 포함)
     * Step 8: FE → POST /api/v1/auth/oacx/easysign (이 API)
     * Step 9: ido → OACX SDK.jwtDecryptResult() → JWT 복호화
     * Step 10: CI 내부 처리 (FE 미반환 Q3=B)
     * Step 11: FE ← {name, birthday, phone}
     * </pre>
     *
     * <p><b>FE 사용 예시:</b>
     * <pre>
     * // usePersonalEasyAuth.ts
     * OACXsdk.open(async (callbackData) => {
     *   // callbackData = { fn: 'authComplete', status: 'success', res: {...} }
     *   const { data } = await beApiInstance.post('/api/v1/auth/oacx/easysign', callbackData);
     *   if (data.resultCode === '2000') {
     *     const { name, birthday, phone } = data;
     *     // CI는 응답에 없음 (보안 정책 Q3=B)
     *   }
     * });
     * </pre>
     *
     * <p><b>응답 코드:</b>
     * <ul>
     *   <li>{@code 2000} — 성공 (name, birthday, phone 포함)</li>
     *   <li>{@code 4000} — fn이 "authComplete"가 아님</li>
     *   <li>{@code 4001} — OACX resultCode가 "200"이 아님 (인증 실패/취소)</li>
     *   <li>{@code 5002} — JWT 복호화 실패</li>
     * </ul>
     *
     * @param request OACX SDK 콜백 데이터 (fn, status, res 포함)
     * @return OACX 인증 결과 응답 (name, birthday, phone — CI 제외)
     */
    @PostMapping("/oacx/easysign")
    public OacxEasysignResponse oacxEasysignCallback(@Valid @RequestBody OacxEasysignRequest request) {
        return authService.handleOacxEasysign(request);
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
