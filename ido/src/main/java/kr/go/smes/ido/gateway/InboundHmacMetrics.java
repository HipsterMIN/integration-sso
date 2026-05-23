package kr.go.smes.ido.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * F4.7 — Inbound HMAC 서명 결과 카운터 (Sprint β-1)
 *
 * <h3>배경</h3>
 * <p>{@link HmacSignatureFilter}는 F-26({@code IDO_HMAC_SIG_REQUIRED}) 기본값 false 상태에서
 * "X-Internal-Sig 헤더 부재 시 무조건 통과(soft mode)" 로 동작한다. 이 점진 도입 의도는 합리적이나,
 * 운영자가 <b>"현재 미준수 기관 비율"</b> 을 알지 못하면 F-26=true 강화 시점을 결정할 수 없다.
 * (Sprint α-3 / 04_handoff_flow.md F4.7 참조)
 *
 * <h3>노출 메트릭</h3>
 * <pre>
 * ido_inbound_hmac_total{result="valid"}            — 헤더 존재 + 검증 성공
 * ido_inbound_hmac_total{result="missing"}          — 헤더 부재 (soft mode 통과)
 * ido_inbound_hmac_total{result="invalid_signature"} — 서명 불일치 (401 응답)
 * ido_inbound_hmac_total{result="missing_agency"}   — X-Agency-Code 부재 (401 응답)
 * ido_inbound_hmac_total{result="key_not_found"}    — 기관 키 미등록 (401 응답)
 * ido_inbound_hmac_total{result="compute_error"}    — HMAC 계산 오류 (401 응답)
 * </pre>
 *
 * <h3>운영 활용</h3>
 * <ul>
 *   <li><b>soft → strict 전환 가드</b>:
 *       {@code rate(ido_inbound_hmac_total{result="missing"}[1h])} 가 충분 기간 0 인 것을 확인 후
 *       {@code IDO_HMAC_SIG_REQUIRED=true} 전환.</li>
 *   <li><b>침해 의심 시</b>:
 *       {@code rate(ido_inbound_hmac_total{result=~"invalid_signature|missing_agency|key_not_found"}[5m])}
 *       이 갑자기 증가하면 공격 가능성 검토.</li>
 * </ul>
 *
 * <h3>태그 카디널리티</h3>
 * <p>{@code result} 는 6 종으로 닫힌 enum (위 목록). agencyCode 등 PII 또는 고-카디널리티 태그는
 * 본 메트릭에 부착하지 않는다 — 필요 시 별도 audit log 로 남긴다 ({@link HmacSignatureFilter} log.warn).
 *
 * @see HmacSignatureFilter
 * @see docs/analysis/sso-im-readiness/04_handoff_flow.md (F4.7)
 */
@Slf4j
@Component
public class InboundHmacMetrics {

    /** Micrometer 카운터 메트릭 이름 (Prometheus 노출 시 {@code ido_inbound_hmac_total}) */
    public static final String METRIC_NAME = "ido.inbound.hmac.total";

    /** 태그 키 */
    public static final String TAG_RESULT = "result";

    /** 태그 값 — 헤더 존재 + 검증 성공 */
    public static final String RESULT_VALID = "valid";
    /** 태그 값 — 헤더 부재 (soft mode 통과) */
    public static final String RESULT_MISSING = "missing";
    /** 태그 값 — 서명 불일치 */
    public static final String RESULT_INVALID_SIGNATURE = "invalid_signature";
    /** 태그 값 — X-Agency-Code 헤더 부재 */
    public static final String RESULT_MISSING_AGENCY = "missing_agency";
    /** 태그 값 — 기관 키 미등록 */
    public static final String RESULT_KEY_NOT_FOUND = "key_not_found";
    /** 태그 값 — HMAC 계산 오류 (시스템 결함) */
    public static final String RESULT_COMPUTE_ERROR = "compute_error";

    private static final String DESCRIPTION =
            "Inbound HMAC 서명 검증 결과 카운터 (F4.7 — soft mode 가시화)";

    private final MeterRegistry registry;

    public InboundHmacMetrics(MeterRegistry registry) {
        this.registry = registry;

        // 시계열 즉시 노출을 위한 사전 등록 (모든 result 값으로 0 초기화)
        for (String result : new String[] {
                RESULT_VALID, RESULT_MISSING, RESULT_INVALID_SIGNATURE,
                RESULT_MISSING_AGENCY, RESULT_KEY_NOT_FOUND, RESULT_COMPUTE_ERROR }) {
            Counter.builder(METRIC_NAME)
                    .description(DESCRIPTION)
                    .tag(TAG_RESULT, result)
                    .register(registry);
        }
    }

    /** 결과 카운터 증가 */
    public void increment(String result) {
        registry.counter(METRIC_NAME, TAG_RESULT, result).increment();
    }

    /** 검증 성공 (헤더 존재 + 서명 일치) */
    public void recordValid()             { increment(RESULT_VALID); }
    /** 헤더 부재 — soft mode 통과 케이스 */
    public void recordMissing()           { increment(RESULT_MISSING); }
    /** 서명 불일치 */
    public void recordInvalidSignature()  { increment(RESULT_INVALID_SIGNATURE); }
    /** X-Agency-Code 헤더 부재 */
    public void recordMissingAgency()     { increment(RESULT_MISSING_AGENCY); }
    /** 기관 키 미등록 */
    public void recordKeyNotFound()       { increment(RESULT_KEY_NOT_FOUND); }
    /** HMAC 계산 오류 (시스템 결함) */
    public void recordComputeError()      { increment(RESULT_COMPUTE_ERROR); }
}
