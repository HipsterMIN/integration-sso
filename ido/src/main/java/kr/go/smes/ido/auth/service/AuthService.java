package kr.go.smes.ido.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.ido.auth.audit.AuthAuditService;
import kr.go.smes.ido.auth.client.IntegrationAuthClient;
import kr.go.smes.ido.auth.client.OacxClient;
import kr.go.smes.ido.auth.dto.*;
import kr.go.smes.ido.auth.dto.im.QimMemberInfo;
import kr.go.smes.ido.auth.dto.im.QimRegisterResponse;
import kr.go.smes.ido.auth.port.ImApiOutPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * 본인인증 비즈니스 로직 서비스 (기업인증 콜백, OACX 간편서명, CI 확인)
 *
 * <p>3가지 인증 처리를 담당:
 * <ol>
 *   <li>{@link #callback} — 기업 간편인증 콜백 수신 및 통합인증 서버 auth-check (Q2=B)</li>
 *   <li>{@link #getOacxAccessInfo} — OACX 전자서명 접근키/토큰 발급</li>
 *   <li>{@link #handleOacxEasysign} — OACX 간편서명 결과 복호화 (Q3=B: CI FE 미반환)</li>
 *   <li>{@link #checkNiceCi} — NICE 본인인증 CI 기반 회원 조회</li>
 * </ol>
 *
 * <p><b>설계 결정 요약:</b>
 * <ul>
 *   <li>Q2=B: {@code POST /api/v1/auth/callback} 엔드포인트 추가 — 향후 FE 기업인증 구현 대비</li>
 *   <li>Q3=B: CI는 FE 응답에 포함하지 않음 — 보안 원칙 (PII 보호)</li>
 * </ul>
 *
 * <p><b>S7-T6 구현 완료:</b>
 * <ul>
 *   <li>CI 처리: OACX 인증 결과 CI → {@link kr.go.smes.ido.auth.port.ImApiOutPort#register} 등록</li>
 *   <li>ciCheck: CI 기반 Q-IM 조회/신규 등록 — {@link ImApiOutPort#findByCi} / {@link ImApiOutPort#register} 연동</li>
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
     * <p><b>TODO(S7-T6):</b> IM API 연동 후 복호화된 CI를 IM API에 등록하는 로직 추가.
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
                String correlationId = decrypted.getOrDefault("correlationId", "oacx-" + System.currentTimeMillis());
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
     * NICE 본인인증 CI 기반 회원 조회/매칭
     *
     * <p>NICE 휴대폰 인증 결과({@code POST /nice/phone/result})에서 수신한 CI로
     * 회원 정보를 조회하거나 신규 등록 처리.
     *
     * <p><b>구현 상태 (S7-T6 완료):</b>
     * {@link ImApiOutPort}를 통해 Q-IM에서 CI 기반 회원 조회/신규 등록을 수행한다.
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

        // S7-T6: CI로 Q-IM 기존 회원 조회 → 미등록 시 신규 등록
        String correlationId = "ci-check-" + System.currentTimeMillis();
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

            // 미등록 사용자: Q-IM에 신규 등록
            log.info("[NICE CI] Q-IM 미등록 사용자 — 신규 등록 처리: mbrDvsnCd={}", mbrDvsnCd);
            AuthResult authResult = AuthResult.builder()
                    .ci(request.getCi())
                    .name(request.getIndvlMbrNm())
                    .build();
            QimRegisterResponse registerResult = imApiOutPort.register(authResult, correlationId);
            log.info("[NICE CI] Q-IM 신규 등록 완료: qimUserId={}", registerResult.getQimUserId());

            authAuditService.publishCiCheckEvent(mbrDvsnCd, "2000", null);
            return CiCheckResponse.builder()
                    .resultCode("2000")
                    .resultMsg("신규 등록 완료")
                    .result(true)
                    .build();

        } catch (Exception e) {
            log.error("[NICE CI] Q-IM 조회/등록 실패: mbrDvsnCd={} err={}", mbrDvsnCd, e.getMessage(), e);
            authAuditService.publishCiCheckEvent(mbrDvsnCd, "5010", e.getMessage());
            return CiCheckResponse.builder()
                    .resultCode("5010")
                    .resultMsg("사용자 정보 처리 실패: " + e.getMessage())
                    .result(false)
                    .build();
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
