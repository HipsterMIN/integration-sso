package io.github.hipstermin.idem.hub.auth.legacy;

import io.github.hipstermin.idem.common.spi.identity.IdentityProviderRegistry;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationCallback;
import io.github.hipstermin.idem.common.spi.identity.VerificationRequest;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import io.github.hipstermin.idem.hub.auth.audit.AuthAuditService;
import io.github.hipstermin.idem.hub.identity.SubjectRegistrationService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 벤더 경로 호환 프록시 (S5a — <b>1 릴리스 deprecated</b>). 코어에는 벤더 클래스가 없고, 이 컨트롤러는 종전 FE 계약
 * ({@code useNicePhoneAuth.ts}·{@code usePersonalEasyAuth.ts}·k6 스모크)을 표준 SPI 흐름으로 위임한다.
 *
 * <pre>
 * GET  /api/v1/auth/nice/phone/url      → NICE_PHONE.initiate   → {resultCode, authUrl, requestNo}
 * POST /api/v1/auth/nice/phone/result   → NICE_PHONE.complete + registry 등록 → {resultCode, resultData{…, di}}
 * POST /api/v1/auth/oacx/access-info    → OACX_EASYSIGN.initiate → {resultCode, fn, accKey, accToken}
 * POST /api/v1/auth/oacx/easysign       → OACX_EASYSIGN.complete + 등록 → {resultCode, name, birthday, phone}
 * </pre>
 * 결과 코드는 종전과 같다(2000 성공 · 4000 입력 오류 · 5001 발급 실패/미구성 · 5002 결과 실패 · 5003 무결성 · 5010 등록 실패).
 * 제공자가 등록되어 있지 않으면(플러그인 꺼짐·SDK 없음) 5001. FE 는 {@code /api/v1/auth/providers/{code}/…} 로 옮겨간다.
 */
@Slf4j
@Deprecated(since = "S5a", forRemoval = true)
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
public class LegacyVendorAuthController {

    static final String NICE_PHONE = "NICE_PHONE";
    static final String OACX_EASYSIGN = "OACX_EASYSIGN";

    private final IdentityProviderRegistry     registry;
    private final SubjectRegistrationService   subjectRegistrationService;
    private final AuthAuditService             authAuditService;

    // ── NICE ────────────────────────────────────────────────────────────────

    @GetMapping("/nice/phone/url")
    public NicePhoneAuthUrlResponse getNicePhoneAuthUrl(@RequestParam(value = "returnUrl", required = false) String returnUrl) {
        Optional<IdentityVerificationProvider> provider = registry.find(NICE_PHONE);
        if (provider.isEmpty()) {
            return NicePhoneAuthUrlResponse.builder().resultCode("5001").resultMsg("NICE 본인인증이 구성되지 않았습니다 (플러그인 비활성)").build();
        }
        String cid = "nice-url-" + UUID.randomUUID();
        try {
            VerificationStart s = provider.get().initiate(new VerificationRequest(cid, returnUrl, Map.of()));
            authAuditService.publishProviderInitiate(NICE_PHONE, s.txId(), "2000", null);
            return NicePhoneAuthUrlResponse.builder().resultCode("2000").resultMsg("성공").authUrl(s.redirectUrl()).requestNo(s.txId()).build();
        } catch (IdentityVerificationException e) {
            authAuditService.publishProviderInitiate(NICE_PHONE, null, legacyCode(e, "5001"), e.getMessage());
            return NicePhoneAuthUrlResponse.builder().resultCode(legacyCode(e, "5001")).resultMsg(e.getMessage()).build();
        } catch (RuntimeException e) {
            log.error("[LegacyAuth] NICE URL 발급 오류", e);
            authAuditService.publishProviderInitiate(NICE_PHONE, null, "5000", e.getMessage());
            return NicePhoneAuthUrlResponse.builder().resultCode("5000").resultMsg("NICE 인증 URL 생성 오류: " + e.getMessage()).build();
        }
    }

    @PostMapping("/nice/phone/result")
    public NicePhoneAuthResultResponse getNicePhoneAuthResult(@Valid @RequestBody NicePhoneAuthResultRequest request) {
        Optional<IdentityVerificationProvider> provider = registry.find(NICE_PHONE);
        if (provider.isEmpty()) {
            return NicePhoneAuthResultResponse.builder().resultCode("5001").resultMsg("NICE 본인인증이 구성되지 않았습니다 (플러그인 비활성)").build();
        }
        String cid = "nice-" + request.getWebTransactionId();
        VerifiedIdentity identity;
        try {
            identity = provider.get().complete(new VerificationCallback(NICE_PHONE, request.getRequestNo(), cid,
                    Map.of("web_transaction_id", request.getWebTransactionId())));
        } catch (IdentityVerificationException e) {
            authAuditService.publishProviderComplete(NICE_PHONE, request.getRequestNo(), legacyCode(e, "5002"), null, null, e.getMessage());
            return NicePhoneAuthResultResponse.builder().resultCode(legacyCode(e, "5002")).resultMsg(e.getMessage()).build();
        } catch (RuntimeException e) {
            log.error("[LegacyAuth] NICE 결과 처리 오류", e);
            authAuditService.publishProviderComplete(NICE_PHONE, request.getRequestNo(), "5000", null, null, e.getMessage());
            return NicePhoneAuthResultResponse.builder().resultCode("5000").resultMsg("NICE 인증 결과 처리 오류: " + e.getMessage()).build();
        }
        SubjectRegistrationService.Result reg;
        try {
            reg = subjectRegistrationService.register(identity, cid);
        } catch (RuntimeException e) {
            log.error("[LegacyAuth] registry 등록 실패 — 인증 중단: {}", e.getMessage());
            authAuditService.publishProviderComplete(NICE_PHONE, request.getRequestNo(), "5010", null, null, "registry 등록 실패: " + e.getMessage());
            return NicePhoneAuthResultResponse.builder().resultCode("5010").resultMsg("사용자 정보 등록 실패: " + e.getMessage()).build();
        }
        authAuditService.publishProviderComplete(NICE_PHONE, request.getRequestNo(), "2000", reg.qimUserId(), reg.newUser(), null);
        // CI 는 FE 로 나가지 않는다 (Q3=B) — subjectKey 를 응답에 싣지 않는다
        return NicePhoneAuthResultResponse.builder().resultCode("2000").resultMsg("성공")
                .resultData(NicePhoneAuthResultResponse.ResultData.builder()
                        .name(identity.name()).birthdate(identity.birthDate()).gender(identity.gender())
                        .nationalInfo(identity.attributes().get("nationalInfo"))
                        .di(identity.attributes().get("di"))
                        .mobileCo(identity.phoneCarrier()).mobileNo(identity.phone())
                        .build())
                .build();
    }

    // ── OACX ────────────────────────────────────────────────────────────────

    @PostMapping("/oacx/access-info")
    public OacxAccessInfoResponse getOacxAccessInfo(@RequestBody String fn) {
        Optional<IdentityVerificationProvider> provider = registry.find(OACX_EASYSIGN);
        if (provider.isEmpty()) {
            authAuditService.publishProviderInitiate(OACX_EASYSIGN, fn, "5001", "OACX 미구성");
            return OacxAccessInfoResponse.builder().resultCode("5001").resultMsg("OACX 전자서명이 구성되지 않았습니다 (플러그인 비활성 또는 SDK 없음)").build();
        }
        try {
            VerificationStart s = provider.get().initiate(new VerificationRequest("oacx-" + UUID.randomUUID(), null, Map.of("fn", fn)));
            authAuditService.publishProviderInitiate(OACX_EASYSIGN, fn, "2000", null);
            return OacxAccessInfoResponse.builder().resultCode("2000").resultMsg("성공").fn(fn)
                    .accKey(s.params().get("accKey")).accToken(s.params().get("accToken")).build();
        } catch (IdentityVerificationException e) {
            authAuditService.publishProviderInitiate(OACX_EASYSIGN, fn, legacyCode(e, "5001"), e.getMessage());
            return OacxAccessInfoResponse.builder().resultCode(legacyCode(e, "5001")).resultMsg(e.getMessage()).build();
        }
    }

    @PostMapping("/oacx/easysign")
    public OacxEasysignResponse oacxEasysignCallback(@Valid @RequestBody OacxEasysignRequest request) {
        // 입력 검증은 제공자 유무와 무관하게 종전과 같은 코드를 돌려준다 (k6 스모크 계약: 잘못된 fn → 200/4000)
        if (!"authComplete".equals(request.getFn())) {
            authAuditService.publishProviderComplete(OACX_EASYSIGN, null, "4000", null, null, "유효하지 않은 fn: " + request.getFn());
            return OacxEasysignResponse.builder().resultCode("4000").resultMsg("유효하지 않은 fn 값: " + request.getFn() + " (예상: authComplete)").build();
        }
        Optional<IdentityVerificationProvider> provider = registry.find(OACX_EASYSIGN);
        if (provider.isEmpty()) {
            return OacxEasysignResponse.builder().resultCode("5001").resultMsg("OACX 전자서명이 구성되지 않았습니다 (플러그인 비활성 또는 SDK 없음)").build();
        }
        String cid = "oacx-" + UUID.randomUUID();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("fn", request.getFn());
        params.put("status", request.getStatus());
        params.put("res", request.getRes() == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(request.getRes()).toString());
        VerifiedIdentity identity;
        try {
            identity = provider.get().complete(new VerificationCallback(OACX_EASYSIGN, request.getFn(), cid, params));
        } catch (IdentityVerificationException e) {
            authAuditService.publishProviderComplete(OACX_EASYSIGN, null, legacyCode(e, "5002"), null, null, e.getMessage());
            return OacxEasysignResponse.builder().resultCode(legacyCode(e, "5002")).resultMsg(e.getMessage()).build();
        }
        SubjectRegistrationService.Result reg;
        try {
            reg = subjectRegistrationService.register(identity, cid);
        } catch (RuntimeException e) {
            authAuditService.publishProviderComplete(OACX_EASYSIGN, null, "5010", null, null, "registry 등록 실패: " + e.getMessage());
            return OacxEasysignResponse.builder().resultCode("5010").resultMsg("사용자 정보 등록 실패: " + e.getMessage()).build();
        }
        authAuditService.publishProviderComplete(OACX_EASYSIGN, identity.attributes().get("provider"), "2000", reg.qimUserId(), reg.newUser(), null);
        return OacxEasysignResponse.builder().resultCode("2000").resultMsg("성공")
                .name(identity.name()).birthday(identity.birthDate()).phone(identity.phone()).build();
    }

    /** 플러그인이 종전 결과 코드를 reasonCode 로 주면 그대로, 아니면 기본 코드. */
    static String legacyCode(IdentityVerificationException e, String fallback) {
        String r = e.getReasonCode();
        return r != null && r.matches("\\d{4}") ? r : fallback;
    }
}
