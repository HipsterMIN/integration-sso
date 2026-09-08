package io.github.hipstermin.idem.hub.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.hub.auth.audit.AuthAuditService;
import io.github.hipstermin.idem.hub.auth.client.IntegrationAuthClient;
import io.github.hipstermin.idem.hub.auth.client.OacxClient;
import io.github.hipstermin.idem.hub.auth.dto.*;
import io.github.hipstermin.idem.hub.auth.dto.im.QimMemberInfo;
import io.github.hipstermin.idem.hub.auth.dto.im.QimRegisterResponse;
import io.github.hipstermin.idem.hub.auth.port.ImApiOutPort;
import io.github.hipstermin.idem.hub.qim.crypto.AesSharedKeyDecryptor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 본인인증 비즈니스 로직 서비스 (기업인증 콜백, OACX 간편서명, CI 확인)
 *
 * <p>3가지 인증 처리를 담당:
 * <ol>
 *   <li>{@link #callback} — 기업 간편인증 콜백 수신 및 통합인증 서버 auth-check (Q2=B)</li>
 *   <li>{@link #getOacxAccessInfo} — OACX 전자서명 접근키/토큰 발급</li>
 *   <li>{@link #handleOacxEasysign} — OACX 간편서명 결과 복호화 (Q3=B: CI FE 미반환)</li>
 *   <li>{@link #checkNiceCi} — NICE 본인인증 CI 기반 회원 조회 (조회 전용)</li>
 * </ol>
 *
 * <p><b>설계 결정 요약:</b>
 * <ul>
 *   <li>Q2=B: {@code POST /api/v1/auth/callback} 엔드포인트 추가 — 향후 FE 기업인증 구현 대비</li>
 *   <li>Q3=B: CI는 FE 응답에 포함하지 않음 — 보안 원칙 (PII 보호)</li>
 * </ul>
 *
 * <p><b>S7-T6 구현 완료 (운영 수준 개선):</b>
 * <ul>
 *   <li>CI 처리: OACX 인증 결과 CI → {@link io.github.hipstermin.idem.hub.auth.port.ImApiOutPort#register} 등록</li>
 *   <li>ciCheck: CI 기반 Q-IM 조회 전용 — 신규 등록 없음 (데이터 품질 보호)
 *       <br>신규 등록은 반드시 {@code POST /nice/phone/result} 또는 OACX easysign 완전 흐름을 통해 수행</li>
 *   <li>correlationId: {@code UUID.randomUUID()} 기반 — 충돌 없는 고유 추적 ID 보장</li>
 * </ul>
 *
 * @see IntegrationAuthClient
 * @see OacxClient
 * @see NiceAuthService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final IntegrationAuthClient integrationAuthClient;
    private final OacxClient oacxClient;
    private final ObjectMapper objectMapper;
    private final ImApiOutPort imApiOutPort;
    private final AuthAuditService authAuditService;
    private final AesSharedKeyDecryptor aesSharedKeyDecryptor;

    /**
     * FE에서 AES-GCM으로 암호화하여 전송한 CI를 복호화하는 키
     *
     * <p>FE의 {@code aesGcm.ts}에서 사용하는 AES-GCM 키.
     * webpack DefinePlugin에 {@code AES_GCM_KEY}로 번들링되는 키와 동일한 값이어야 한다.
     *
     * <p>운영: {@code FE_AES_GCM_KEY} 환경변수 필수 설정 (32바이트, Base64 인코딩)
     */
    @Value("${ido.fe-aes-gcm-key:}")
    private String feAesGcmKey;

    /**
     * FE AES-GCM 키 제공 (B-1)
     *
     * <p>FE 번들({@code AES_GCM_KEY} webpack DefinePlugin)에 AES-GCM 키를 포함하지 않고
     * 서버 사이드에서 런타임에 키를 제공한다. FE는 앱 초기화 시 이 엔드포인트를 호출하여
     * 키를 주입받아야 한다.
     *
     * <p><b>보안 원칙 (B-1):</b>
     * FE 번들에 AES-GCM 키가 포함되면 번들 분석으로 키가 노출된다.
     * 이 메서드는 키를 런타임에 주입하여 번들 노출 위험을 차단한다.
     *
     * <p><b>운영 설정:</b>
     * {@code ido.fe-aes-gcm-key} (환경변수: {@code FE_AES_GCM_KEY}) 필수.
     * 미설정 시 {@link IllegalStateException} 발생 → 500 응답.
     *
     * @return FE AES-GCM 키 응답 ({@code aesGcmKey} 필드)
     * @throws IllegalStateException FE_AES_GCM_KEY 환경변수 미설정 시
     */
    public Map<String, String> getFeAesGcmKey() {
        if (feAesGcmKey == null || feAesGcmKey.isBlank()) {
            log.error("[AES-GCM-KEY][보안경고] ido.fe-aes-gcm-key 미설정 — FE_AES_GCM_KEY 환경변수를 설정하세요.");
            throw new IllegalStateException("서버 설정 오류: FE_AES_GCM_KEY가 설정되지 않았습니다.");
        }
        log.debug("[AES-GCM-KEY] FE AES-GCM 키 제공 완료");
        return Map.of("aesGcmKey", feAesGcmKey);
    }

    /**
     * 기업 간편인증 콜백 수신 및 auth-check 처리 (Q2=B)
     *
     * <p>통합인증 간편인증창이 FE에 postMessage로 전달한 콜백 데이터를
     * 통합인증 서버에 전달하여 기업 인증 결과를 확인한다.
     *
     * <p><b>Q2=B 결정 이유:</b>
     * onepass-fe의 FE Step3(기업인증) 코드에 "미구현" 주석 다수 존재.
     * 향후 FE에서 기업인증 구현 시 이 엔드포인트를 사용할 예정.
     * 현재 FE는 이 API를 호출하지 않음 — 미래 대비 선제 구현.
     *
     * <p><b>resultData 처리:</b>
     * 통합인증 서버는 resultData를 JSON 직렬화 후 Base64 인코딩하여 반환.
     * {@link #decodeResultData}에서 디코딩 처리.
     *
     * <p><b>보안 정책:</b>
     * CI는 기업인증 결과에 포함되지 않음 (개인 vs 기업 인증 구분).
     * 기업인증의 PII(businessNumber 등)는 FE 응답에 최소 필드만 포함.
     *
     * @param request FE에서 수신한 간편인증 콜백 요청 (txId, tokenId, siteInfo 등)
     * @return 기업인증 결과 응답 (name, businessNumber, birth, phone, bizOpendt)
     */
    public AuthCallbackResponse callback(AuthCallbackRequest request) {
        String siteId = request.getSiteInfo() == null ? null : request.getSiteInfo().getSiteId();
        log.info("[기업인증] 콜백 수신: siteId={}, txId={}, tokenId={}",
                siteId, request.getTxId(), request.getTokenId());

        try {
            AuthCheckResponse response = integrationAuthClient.sendAuthCheck(request);
            log.info("[기업인증] auth-check 응답: resultCode={}", response != null ? response.getResultCode() : "null");

            if (response == null) {
                authAuditService.publishCallbackEvent(request.getTxId(), "5001", "기업인증 서버 응답 없음");
                return AuthCallbackResponse.builder()
                        .resultCode("5001")
                        .resultMsg("기업인증 서버 응답 없음")
                        .build();
            }

            // Base64 인코딩된 resultData 디코딩
            AuthCheckResponse.ResultData data = decodeResultData(response.getResultData());

            // FE 반환 데이터는 최소 필드만 포함 (certInfo, signedDataLst 등 내부 필드 제외)
            AuthCallbackResponse.ResultData slimData = data == null ? null :
                    AuthCallbackResponse.ResultData.builder()
                            .name(data.getName())
                            .businessNumber(data.getBusinessNumber())
                            .birth(data.getBirth())
                            .phone(data.getPhone())
                            .bizOpendt(data.getBizOpendt())
                            .build();

            // 감사 로그: 기업인증 콜백 성공/실패
            boolean callbackSuccess = "2000".equals(response.getResultCode());
            authAuditService.publishCallbackEvent(
                    request.getTxId(),
                    response.getResultCode(),
                    callbackSuccess ? null : response.getResultMsg());

            return AuthCallbackResponse.builder()
                    .resultCode(response.getResultCode())
                    .resultMsg(response.getResultMsg())
                    .resultData(slimData)
                    .build();

        } catch (Exception e) {
            log.error("[기업인증] 콜백 처리 중 오류 발생", e);
            authAuditService.publishCallbackEvent(request.getTxId(), "5000", e.getMessage());
            return AuthCallbackResponse.builder()
                    .resultCode("5000")
                    .resultMsg("기업인증 처리 오류: " + e.getMessage())
                    .build();
        }
    }

    /**
     * OACX 전자서명 접근키/토큰 발급
     *
     * <p>FE의 OACX JS SDK 초기화에 필요한 accKey, accToken을 발급한다.
     * OACX SDK({@code OacxClient})가 OACX 서버와 통신하여 접근 정보를 생성.
     *
     * <p><b>FE 사용 방법:</b>
     * <pre>
     * // POST /api/v1/auth/oacx/access-info
     * // body: "simpleAuth"  (plain string)
     * const { fn, accKey, accToken } = await response.json();
     * OACXsdk.init({ fn, accKey, accToken });
     * </pre>
     *
     * @param fn OACX 기능 코드 ("simpleAuth" 고정)
     * @return OACX 접근 정보 응답 (fn, accKey, accToken)
     */
    public OacxAccessInfoResponse getOacxAccessInfo(String fn) {
        log.info("[OACX] getAccessInfo 요청: fn={}", fn);
        OacxAccessInfoResponse response = oacxClient.getAccessInfo(fn);
        authAuditService.publishOacxAccessInfoEvent(fn,
                response != null ? response.getResultCode() : "5000");
        return response;
    }

    /**
     * OACX 간편서명 콜백 처리 및 사용자 정보 반환 (Q3=B: CI FE 미반환)
     *
     * <p>OACX JS SDK 콜백 데이터를 수신하여 JWT를 복호화하고 사용자 정보를 추출.
     *
     * <p><b>OACX 인증 완료 플로우 (Step 7~11):</b>
     * <pre>
     * Step 7: OACX JS SDK 간편서명 완료 → FE 콜백 발생 (fn, status, res)
     * Step 8: FE → POST /api/v1/auth/oacx/easysign (이 메서드)
     * Step 9: OacxClient.decryptEasysignResult() → JWT 복호화
     * Step 10: 사용자 정보 추출 (provider별 키 이름 통일 처리)
     * Step 11: FE에 {name, birthday, phone} 반환 (CI 제외 — Q3=B)
     * </pre>
     *
     * <p><b>보안 정책 (Q3=B):</b>
     * OACX 복호화 결과에 CI가 포함되어 있어도 FE에 반환하지 않음.
     * {@code OacxEasysignResponse}에 ci 필드가 null로 설정되어 {@code @JsonInclude(NON_NULL)}에 의해
     * 응답 JSON에서 자동 제외됨.
     *
     * <p><b>OACX provider별 키 이름 차이:</b>
     * <ul>
     *   <li>naver/toss/dream/banksalad: {@code name}, {@code phone}</li>
     *   <li>PASS (통신3사): {@code userNm}, {@code phoneNo}</li>
     * </ul>
     *
     * <p><b>S7-T6 구현 완료:</b> 복호화된 CI를 {@link io.github.hipstermin.idem.hub.auth.port.ImApiOutPort#register}를
     * 통해 Q-IM에 등록. CI 등록 실패 시 인증 플로우 중단(resultCode=5010) — CI 미등록 상태로의
     * 진행은 데이터 정합성 위반이므로 허용하지 않음.
     *
     * @param request OACX SDK 콜백 데이터 (fn, status, res 포함)
     * @return OACX 인증 결과 응답 (name, birthday, phone — CI 제외)
     */
    public OacxEasysignResponse handleOacxEasysign(OacxEasysignRequest request) {
        log.info("[OACX] easysign 콜백: fn={}, status={}", request.getFn(), request.getStatus());

        // fn 검증: "authComplete"가 아니면 잘못된 요청
        if (!"authComplete".equals(request.getFn())) {
            log.warn("[OACX] 예상치 못한 fn 값: {}", request.getFn());
            authAuditService.publishOacxEasysignEvent(null, "4000",
                    "유효하지 않은 fn: " + request.getFn());
            return OacxEasysignResponse.builder()
                    .resultCode("4000")
                    .resultMsg("유효하지 않은 fn 값: " + request.getFn() + " (예상: authComplete)")
                    .build();
        }

        // OACX 내부 resultCode 검증
        String oacxResultCode = request.getRes() != null
                ? String.valueOf(request.getRes().get("resultCode"))
                : null;
        if (!"200".equals(oacxResultCode)) {
            log.warn("[OACX] 인증 실패 또는 취소: resultCode={}", oacxResultCode);
            authAuditService.publishOacxEasysignEvent(null, "4001",
                    "OACX 인증 실패: resultCode=" + oacxResultCode);
            return OacxEasysignResponse.builder()
                    .resultCode("4001")
                    .resultMsg("OACX 인증 실패: resultCode=" + oacxResultCode)
                    .build();
        }

        // OACX SDK로 JWT 복호화 (SDK는 fn/status/res 전체 맵을 요구)
        Map<String, Object> fullCallbackMap = Map.of(
                "fn", request.getFn(),
                "status", request.getStatus(),
                "res", request.getRes()
        );
        Map<String, String> decrypted = oacxClient.decryptEasysignResult(fullCallbackMap);

        if (!"success".equals(decrypted.get("status"))) {
            log.error("[OACX] JWT 복호화 실패: status={}, message={}",
                    decrypted.get("status"), decrypted.get("message"));
            authAuditService.publishOacxEasysignEvent(null, "5002",
                    "JWT 복호화 실패: " + decrypted.get("message"));
            return OacxEasysignResponse.builder()
                    .resultCode("5002")
                    .resultMsg("OACX 인증 결과 복호화 실패")
                    .build();
        }

        // CI 내부 처리 (FE 미반환 — Q3=B)
        log.debug("[OACX] 복호화 결과 keys={}", decrypted.keySet());
        String ciForInternalUse = decrypted.get("ci");

        // OACX provider별 키 이름 차이 통일 처리
        // - naver/toss/dream/banksalad: name, phone
        // - PASS(통신3사): userNm, phoneNo
        String name = decrypted.getOrDefault("name", decrypted.get("userNm"));
        String phone = decrypted.getOrDefault("phone", decrypted.get("phoneNo"));

        // S7-T6: CI → Q-IM 등록 (Q3=B: CI는 FE 미반환, Q-IM에만 전달)
        if (ciForInternalUse != null && !ciForInternalUse.isBlank()) {
            try {
                String correlationId = "oacx-" + UUID.randomUUID();
                AuthResult authResult = AuthResult.builder()
                        .ci(ciForInternalUse)
                        .di(decrypted.get("di"))
                        .name(name)
                        .birthday(decrypted.get("birthday"))
                        .gender(decrypted.get("gender"))
                        .mobile(phone)
                        .mobileCorp(decrypted.get("mobileCorp"))
                        .build();
                QimRegisterResponse registerResult = imApiOutPort.register(authResult, correlationId);
                log.info("[OACX] Q-IM 등록 완료: qimUserId={} isNew={}", registerResult.getQimUserId(), registerResult.getIsNew());
            } catch (Exception e) {
                // Q-IM 등록 실패 시 인증 플로우 중단 (CI 미등록 상태로 진행 불가)
                log.error("[OACX] Q-IM 등록 실패 — 인증 중단: {}", e.getMessage(), e);
                authAuditService.publishOacxEasysignEvent(
                        decrypted.get("provider"), "5010", "Q-IM 등록 실패: " + e.getMessage());
                return OacxEasysignResponse.builder()
                        .resultCode("5010")
                        .resultMsg("사용자 정보 등록 실패: " + e.getMessage())
                        .build();
            }
        } else {
            log.warn("[OACX] CI 미포함 — Q-IM 등록 건너뜀 (provider가 CI를 미제공)");
        }

        log.info("[OACX] 인증 성공: name={}", name);

        // 감사 로그: OACX 간편서명 성공 (provider 정보는 PII 없음)
        authAuditService.publishOacxEasysignEvent(decrypted.get("provider"), "2000", null);

        // CI는 FE 미반환 (Q3=B) — OacxEasysignResponse.ci 필드를 null로 유지
        return OacxEasysignResponse.builder()
                .resultCode("2000")
                .resultMsg("성공")
                // .ci(decrypted.get("ci"))  ← Q3=B: 의도적으로 CI 미설정
                .name(name)
                .birthday(decrypted.get("birthday"))
                .phone(phone)
                .build();
    }

    /**
     * NICE 본인인증 CI 기반 회원 조회 (조회 전용 — 신규 등록 없음)
     *
     * <p>NICE 휴대폰 인증 결과({@code POST /nice/phone/result})에서 수신한 CI로
     * Q-IM에 기등록된 회원 정보를 조회한다.
     *
     * <p><b>설계 원칙 (운영 수준):</b>
     * 이 메서드는 CI 기반 회원 조회 전용이다. 신규 사용자 등록은 수행하지 않는다.
     * 올바른 인증 흐름이라면 {@code POST /nice/phone/result} 처리 시점에 Q-IM 등록이
     * 이미 완료된 상태여야 한다. 해당 시점에 name/birthday/gender/mobile 등
     * 완전한 프로필이 함께 등록된다.
     *
     * ci-check 시점에 Q-IM에 CI가 없다면 이는 선행 인증 플로우 미완료를 의미하며,
     * 불완전한 프로필로 신규 등록하는 것보다 명시적 에러 반환이 안전하다.
     * (100만+ 사용자 대상 DB에 불완전한 프로필 레코드 생성 방지)
     *
     * <p><b>유효성 검증 규칙:</b>
     * <ul>
     *   <li>ci: 필수. NICE에서 반환한 88자 CI</li>
     *   <li>mbrDvsnCd: 필수. "A101"(개인) 또는 "A102"(기업)</li>
     *   <li>bizno: mbrDvsnCd=A102일 때 필수</li>
     * </ul>
     *
     * <p><b>보안 주의:</b>
     * CI는 로그에 전체를 출력하지 않음. 로그에는 앞 8자만 표시.
     *
     * @param request CI 확인 요청 (ci, mbrDvsnCd, bizno 등)
     * @return CI 확인 결과 (result: boolean, indvlMbrId/cmpMbrId: 기존 회원인 경우)
     */
    public CiCheckResponse checkNiceCi(CiCheckRequest request) {
        // CI 로그 마스킹 (보안: 앞 8자만 표시)
        String ciMasked = request.getCi() != null && request.getCi().length() > 8
                ? request.getCi().substring(0, 8) + "..."
                : "(null)";
        log.info("[NICE CI] 확인 요청: ci={}, mbrDvsnCd={}, cmpMbrId={}, bizno={}",
                ciMasked, request.getMbrDvsnCd(), request.getCmpMbrId(), request.getBizno());

        // 1. CI 필수 검증
        if (request.getCi() == null || request.getCi().isBlank()) {
            return CiCheckResponse.builder()
                    .resultCode("4000")
                    .resultMsg("ci 필드가 필요합니다")
                    .build();
        }

        // 2. 회원구분코드 검증
        String mbrDvsnCd = request.getMbrDvsnCd();
        if (!"A101".equals(mbrDvsnCd) && !"A102".equals(mbrDvsnCd)) {
            return CiCheckResponse.builder()
                    .resultCode("4000")
                    .resultMsg("mbrDvsnCd 값이 유효하지 않습니다. 허용값: A101(개인), A102(기업)")
                    .build();
        }

        // 3. 기업회원 필수 파라미터 검증
        if ("A102".equals(mbrDvsnCd)) {
            if (request.getBizno() == null || request.getBizno().isBlank()) {
                return CiCheckResponse.builder()
                        .resultCode("4000")
                        .resultMsg("기업회원(A102)은 bizno(사업자등록번호)가 필수입니다")
                        .build();
            }
        }

        // CI 기반 Q-IM 기존 회원 조회 (조회 전용 — 신규 등록 없음)
        // 정상 흐름: POST /nice/phone/result에서 Q-IM 등록 완료 후 이 API 호출
        String correlationId = "ci-check-" + UUID.randomUUID();
        try {
            Optional<QimMemberInfo> existing = imApiOutPort.findByCi(request.getCi(), mbrDvsnCd, correlationId);

            if (existing.isPresent()) {
                // 기존 회원: Q-IM에서 조회된 indvlMbrId / cmpMbrId 반환
                QimMemberInfo info = existing.get();
                log.info("[NICE CI] 기존 회원 조회 성공: qimUserId={} mbrDvsnCd={}", info.getQimUserId(), mbrDvsnCd);
                authAuditService.publishCiCheckEvent(mbrDvsnCd, "2000", null);
                return CiCheckResponse.builder()
                        .resultCode("2000")
                        .resultMsg("기존 회원")
                        .result(true)
                        .indvlMbrId(info.getIndvlMbrId())
                        .cmpMbrId(info.getCmpMbrId())
                        .build();
            }

            // CI 미등록 — 선행 인증 플로우(POST /nice/phone/result) 미완료로 간주
            // 불완전한 프로필(name만)로 신규 등록하지 않음: 운영 DB 데이터 품질 보호
            log.warn("[NICE CI] Q-IM 미등록 CI — 선행 인증 미완료 또는 비정상 접근: ci={} mbrDvsnCd={}", ciMasked, mbrDvsnCd);
            authAuditService.publishCiCheckEvent(mbrDvsnCd, "4040",
                    "Q-IM 미등록 CI: 선행 인증(POST /nice/phone/result) 미완료");
            return CiCheckResponse.builder()
                    .resultCode("4040")
                    .resultMsg("본인인증 이력이 없습니다. 먼저 NICE 휴대폰 본인인증을 완료해 주세요.")
                    .result(false)
                    .build();

        } catch (Exception e) {
            log.error("[NICE CI] Q-IM 조회 실패: mbrDvsnCd={} err={}", mbrDvsnCd, e.getMessage(), e);
            authAuditService.publishCiCheckEvent(mbrDvsnCd, "5010", e.getMessage());
            return CiCheckResponse.builder()
                    .resultCode("5010")
                    .resultMsg("사용자 정보 조회 실패: " + e.getMessage())
                    .result(false)
                    .build();
        }
    }

    /**
     * FE → ido → Q-IM CI 토큰 교환 (Q3=B 구현)
     *
     * <p>FE가 전달한 AES-GCM 암호화 CI를 복호화하고, Q-IM 공유키로 재암호화하여
     * Q-IM에 등록 후 ciToken을 발급받아 반환한다.
     *
     * <p><b>처리 플로우</b>:
     * <ol>
     *   <li>FE 전송 암호화 CI 수신 (Base64: IV[12] || CipherText+Tag)</li>
     *   <li>FE AES-GCM 키({@code ido.fe-aes-gcm-key})로 복호화 → CI 평문</li>
     *   <li>CI 평문을 Q-IM 공유키로 재암호화({@link AesSharedKeyDecryptor#encrypt})</li>
     *   <li>Q-IM {@code POST /api/v1/internal/users/register} 호출</li>
     *   <li>Q-IM이 발급한 qimUserId를 ciToken으로 반환</li>
     * </ol>
     *
     * <p><b>보안 원칙 (Q3=B)</b>:
     * CI 원문은 이 메서드 스코프 내에서만 존재하며, 응답 DTO에는 포함되지 않는다.
     * ciToken은 Q-IM qimUserId 기반 불투명 식별자로, 회원 조회 시 사용한다.
     *
     * @param request FE 요청 (encryptedCi, mbrDvsnCd, bizno)
     * @return ciToken 교환 결과 (resultCode, ciToken, qimUserId)
     */
    public CiTokenExchangeResponse exchangeCiToken(CiTokenExchangeRequest request) {
        String mbrDvsnCd = request.getMbrDvsnCd();
        log.info("[CI-TOKEN] CI 토큰 교환 요청: mbrDvsnCd={}", mbrDvsnCd);

        // 1. FE AES-GCM 키 설정 검증
        if (feAesGcmKey == null || feAesGcmKey.isBlank()) {
            log.error("[CI-TOKEN][보안경고] ido.fe-aes-gcm-key 미설정 — CI 복호화 불가. FE_AES_GCM_KEY 환경변수를 설정하세요.");
            return CiTokenExchangeResponse.builder()
                    .resultCode("5000")
                    .resultMsg("서버 설정 오류: FE AES-GCM 키가 설정되지 않았습니다.")
                    .build();
        }

        // 2. 회원 구분 코드 검증
        if (!"A101".equals(mbrDvsnCd) && !"A102".equals(mbrDvsnCd)) {
            return CiTokenExchangeResponse.builder()
                    .resultCode("4000")
                    .resultMsg("mbrDvsnCd 값이 유효하지 않습니다. 허용값: A101(개인), A102(기업)")
                    .build();
        }
        if ("A102".equals(mbrDvsnCd) && (request.getBizno() == null || request.getBizno().isBlank())) {
            return CiTokenExchangeResponse.builder()
                    .resultCode("4000")
                    .resultMsg("기업회원(A102)은 bizno(사업자등록번호)가 필수입니다.")
                    .build();
        }

        // 3. FE AES-GCM 복호화 (암호화 CI → CI 평문)
        String plainCi;
        try {
            plainCi = decryptFeAesGcm(request.getEncryptedCi());
        } catch (Exception e) {
            log.warn("[CI-TOKEN] FE AES-GCM 복호화 실패: {}", e.getMessage());
            return CiTokenExchangeResponse.builder()
                    .resultCode("4010")
                    .resultMsg("CI 복호화 실패: AES-GCM 키 불일치 또는 암호문 형식 오류. " + e.getMessage())
                    .build();
        }

        // 4. CI 평문 기본 검증 (NICE CI는 88자)
        if (plainCi == null || plainCi.isBlank()) {
            log.warn("[CI-TOKEN] 복호화 결과 CI가 비어있음");
            return CiTokenExchangeResponse.builder()
                    .resultCode("4010")
                    .resultMsg("복호화된 CI가 비어있습니다.")
                    .build();
        }

        // 5. Q-IM에 CI 등록 (Q-IM 공유키로 재암호화하여 전달 — AesSharedKeyDecryptor.encrypt() 내부 처리)
        String correlationId = "ci-token-" + UUID.randomUUID();
        try {
            AuthResult authResult = AuthResult.builder()
                    .ci(plainCi)
                    .build();
            QimRegisterResponse qimResult = imApiOutPort.register(authResult, correlationId);

            log.info("[CI-TOKEN] Q-IM 등록 완료: qimUserId={} isNew={} mbrDvsnCd={}",
                    qimResult.getQimUserId(), qimResult.getIsNew(), mbrDvsnCd);

            // 6. ciToken = qimUserId (불투명 식별자, CI 원문 미포함)
            return CiTokenExchangeResponse.builder()
                    .resultCode("2000")
                    .resultMsg("성공")
                    .ciToken(qimResult.getQimUserId())
                    .qimUserId(qimResult.getQimUserId())
                    .build();

        } catch (Exception e) {
            log.error("[CI-TOKEN] Q-IM 등록/ciToken 발급 실패: mbrDvsnCd={} correlationId={} err={}",
                    mbrDvsnCd, correlationId, e.getMessage(), e);
            return CiTokenExchangeResponse.builder()
                    .resultCode("5010")
                    .resultMsg("CI 토큰 발급 실패: " + e.getMessage())
                    .build();
        }
    }

    /**
     * FE가 AES-GCM으로 암호화하여 전달한 CI를 복호화한다.
     *
     * <p>FE 암호화 형식: {@code Base64(IV[12 bytes] || CipherText+Tag[len+16 bytes])}
     * GCM 태그 길이: 128비트(16바이트).
     *
     * <p><b>키 형식</b>: {@code feAesGcmKey}는 Base64 인코딩된 32바이트(AES-256) 또는
     * raw 32바이트 문자열(FE webpack에서는 일반적으로 raw 문자열 사용).
     *
     * @param encryptedCi FE가 AES-GCM 암호화한 CI (Base64 인코딩)
     * @return CI 평문
     * @throws IllegalArgumentException 복호화 실패 시
     */
    private String decryptFeAesGcm(String encryptedCi) {
        try {
            // 1. Base64 디코딩
            byte[] combined = Base64.getDecoder().decode(encryptedCi);
            if (combined.length < 12) {
                throw new IllegalArgumentException("암호문이 너무 짧습니다 (최소 12바이트 IV 필요)");
            }

            // 2. IV 추출 (앞 12바이트)
            byte[] iv = new byte[12];
            System.arraycopy(combined, 0, iv, 0, 12);

            // 3. 암호문+태그 추출 (나머지)
            byte[] cipherBytes = new byte[combined.length - 12];
            System.arraycopy(combined, 12, cipherBytes, 0, cipherBytes.length);

            // 4. AES-GCM 복호화
            // FE에서 사용하는 키는 UTF-8 바이트 또는 Base64 디코딩된 바이트
            byte[] keyBytes;
            try {
                // Base64 인코딩된 키 시도
                keyBytes = Base64.getDecoder().decode(feAesGcmKey);
            } catch (Exception e) {
                // Base64가 아니면 UTF-8 바이트로 사용
                keyBytes = feAesGcmKey.getBytes(StandardCharsets.UTF_8);
            }

            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);
            byte[] plainBytes = cipher.doFinal(cipherBytes);

            return new String(plainBytes, StandardCharsets.UTF_8);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("FE AES-GCM 복호화 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 통합인증 서버 resultData Base64 디코딩
     *
     * <p>통합인증 서버는 ResultData를 JSON 직렬화 후 Base64 인코딩하여 전달.
     * 이 메서드에서 디코딩 및 역직렬화를 처리.
     *
     * @param base64 Base64 인코딩된 resultData 문자열
     * @return 디코딩된 ResultData (null이면 null 반환)
     * @throws IllegalStateException Base64 디코딩 또는 JSON 파싱 실패
     */
    private AuthCheckResponse.ResultData decodeResultData(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            return objectMapper.readValue(decoded, AuthCheckResponse.ResultData.class);
        } catch (Exception e) {
            throw new IllegalStateException("[기업인증] resultData Base64 디코딩/파싱 실패", e);
        }
    }
}
