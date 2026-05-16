package kr.go.smes.ido.conversion;

import jakarta.validation.Valid;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.conversion.dto.ConversionInitRequest;
import kr.go.smes.ido.conversion.dto.ConversionInitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 유관기관 → OnePass 회원 전환 초기화 API
 *
 * <p>유관기관이 자체 로그인 완료 후 사용자를 OnePass 전환 플로우로 진입시키기 위해
 * FE Step1.tsx가 호출하는 엔드포인트.
 *
 * <h2>호출 흐름</h2>
 * <pre>
 * 기관 서버 → (signed_request JWT 구성) → 302 redirect → /conversion/step1?signed_request=...
 * FE Step1  → POST /api/v1/conversion/init { signedRequest, agencyCode }
 * IdO       → JWT 검증 + redirectUri 화이트리스트 검증 + ConversionSession 생성
 * FE Step1  → 응답의 conversionSessionId를 ConversionContext에 저장 후 step2 진행
 * </pre>
 *
 * <h2>보안 설계</h2>
 * <ul>
 *   <li>mbrId, redirectUri, returnClient — JWT 내부에 은닉 + HMAC-SHA256 서명으로 변조 방지</li>
 *   <li>redirectUri — agency_meta.callback_whitelist DB 검증 (CallbackUrlValidator)</li>
 *   <li>exp — 5분 이내 요청만 허용 (CONVERSION_REQUEST_EXPIRED)</li>
 *   <li>ConversionSession — Redis TTL 30분, 이후 단계는 sessionId 참조 (민감값 서버 보관)</li>
 * </ul>
 *
 * <h2>레거시 호환</h2>
 * <p>이전 방식(평문 쿼리파라미터 redirect_uri, mbrId 등)은 FE Step1.tsx에서 직접 처리하며
 * 이 API는 신규 signed_request 방식 전용이다. 레거시 방식은 호환 기간 운영 후 제거 예정.
 *
 * @see ConversionInitService
 * @see kr.go.smes.ido.handoff.validate.CallbackUrlValidator
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/conversion")
@RequiredArgsConstructor
public class ConversionInitController {

    private final ConversionInitService conversionInitService;

    /**
     * 전환 초기화 — signed_request JWT 검증 후 ConversionSession 발급
     *
     * <p>POST /api/v1/conversion/init
     *
     * @param correlationId X-Correlation-Id 헤더 (선택, 미전달 시 서버 생성)
     * @param req           signed_request(JWT) + agencyCode
     * @return 200 OK — { conversionSessionId, userType, expiresAt }
     * @throws kr.go.smes.common.error.PlatformException
     *         AGENCY_NOT_FOUND         — agencyCode에 해당하는 기관 없음 또는 비활성
     *         CONVERSION_SIGNATURE_INVALID — JWT 서명 검증 실패
     *         CONVERSION_REQUEST_EXPIRED   — JWT exp 만료 (5분 초과)
     *         AGENCY_CALLBACK_BLOCKED  — redirectUri가 callback_whitelist에 없음
     */
    @PostMapping("/init")
    public ResponseEntity<ConversionInitResponse> init(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody ConversionInitRequest req) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        log.info("[ConversionInit] 전환 초기화 요청: agencyCode={} cid={}", req.getAgencyCode(), cid);

        ConversionSession session = conversionInitService.initiate(req, cid);

        log.info("[ConversionInit] ConversionSession 발급 완료: sessionId={} agencyCode={} cid={}",
                session.getSessionId(), session.getAgencyCode(), cid);

        return ResponseEntity.ok(ConversionInitResponse.builder()
                .conversionSessionId(session.getSessionId())
                .userType(session.getUserType())
                .expiresAt(session.getExpiresAt())
                .build());
    }
}
