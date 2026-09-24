package io.github.hipstermin.idem.registry.kr.api;

import io.github.hipstermin.idem.registry.kr.biz.BizMemberConversionRequest;
import io.github.hipstermin.idem.registry.kr.biz.BizMemberConversionService;
import io.github.hipstermin.idem.registry.kr.biz.BizMemberResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 기업회원 전환 API 컨트롤러
 *
 * <p>보안: X-Internal-Api-Key 헤더 검증 (InternalApiKeyInterceptor) 적용
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>POST /api/v1/internal/biz-members/convert  — 기업회원 전환</li>
 *   <li>GET  /api/v1/internal/biz-members/{qimUserId} — 기업회원 조회</li>
 * </ul>
 *
 * <p>설계서 §P3-06 참조
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/biz-members")
@RequiredArgsConstructor
public class BizMemberConversionController {

    private final BizMemberConversionService bizMemberConversionService;

    /**
     * 기업회원 전환
     *
     * <pre>
     * POST /api/v1/internal/biz-members/convert
     * {
     *   "qimUserId":    "...",
     *   "bizRegNo":     "123-45-67890",
     *   "companyName":  "주식회사 예제",
     *   "repName":      "홍길동",
     *   "bizType":      "소프트웨어 개발",
     *   "correlationId":"..."
     * }
     * </pre>
     *
     * <ul>
     *   <li>200 OK — 전환 완료</li>
     *   <li>400 E-IM-215 — 사업자등록번호 형식 오류</li>
     *   <li>404 E-IM-201 — 사용자 없음</li>
     *   <li>409 E-IM-216 — 중복 사업자등록번호</li>
     * </ul>
     */
    @PostMapping("/convert")
    public ResponseEntity<BizMemberResult> convert(@Valid @RequestBody BizConvertApiRequest req) {
        log.info("[BizAPI] 기업회원 전환 요청: qimUserId={} correlationId={}",
                req.qimUserId(), req.correlationId());

        BizMemberConversionRequest serviceReq = BizMemberConversionRequest.builder()
                .qimUserId(req.qimUserId())
                .bizRegNo(req.bizRegNo())
                .companyName(req.companyName())
                .repName(req.repName())
                .bizType(req.bizType())
                .build();

        BizMemberResult result = bizMemberConversionService.convert(serviceReq, req.correlationId());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * 기업회원 정보 조회
     *
     * <pre>
     * GET /api/v1/internal/biz-members/{qimUserId}
     * </pre>
     *
     * <ul>
     *   <li>200 OK — 조회 성공</li>
     *   <li>404 E-IM-217 — 기업회원 정보 없음</li>
     * </ul>
     */
    @GetMapping("/{qimUserId}")
    public ResponseEntity<BizMemberResult> findByQimUserId(
            @PathVariable String qimUserId,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "N/A") String correlationId) {
        log.info("[BizAPI] 기업회원 조회: qimUserId={}", qimUserId);
        BizMemberResult result = bizMemberConversionService.findByQimUserId(qimUserId, correlationId);
        return ResponseEntity.ok(result);
    }

    /** 기업회원 전환 API 요청 DTO */
    public record BizConvertApiRequest(
            @NotBlank(message = "qimUserId는 필수입니다.")
            String qimUserId,
            @NotBlank(message = "bizRegNo는 필수입니다.")
            String bizRegNo,
            @NotBlank(message = "companyName은 필수입니다.")
            String companyName,
            String repName,
            String bizType,
            @NotBlank(message = "correlationId는 필수입니다.")
            String correlationId) {
    }
}
