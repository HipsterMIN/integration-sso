package kr.go.smes.qim.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * DI(중복가입확인정보) 생성 서비스
 *
 * <p>DI = HMAC-SHA256({siteCode}:{qimUserId}:{serviceSecret}) → Base64URL
 *
 * <p>특성:
 * <ul>
 *   <li>기관별 DI 독립 — 기관 A의 DI ≠ 기관 B의 DI</li>
 *   <li>동일 기관+사용자 조합 → 항상 동일 DI (결정론적)</li>
 *   <li>DI로부터 qimUserId 역산 불가 (단방향 HMAC)</li>
 *   <li>di_map JSON 컬럼에 {"AGENCY_CODE": "di_value"} 형태로 저장</li>
 * </ul>
 *
 * <p>환경변수: {@code QIM_DI_SECRET} (서비스 공통 비밀키, 운영 시 반드시 교체)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiGenerationService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Value("${qim.crypto.di.secret:default-di-secret-change-in-production}")
    private String diSecret;

    private final ObjectMapper objectMapper;

    /**
     * 기관별 DI 조회 또는 신규 생성
     *
     * @param qimUserId   Q-IM 사용자 ID (정본)
     * @param agencyCode  기관 코드
     * @param diMapJson   현재 저장된 di_map JSON (null이면 빈 맵으로 처리)
     * @return DI 값 (Base64URL, 44자)
     */
    public String getOrCreateDi(String qimUserId, String agencyCode, String diMapJson) {
        Map<String, String> diMap = parseDiMap(diMapJson);

        // 이미 존재하는 DI 반환 (결정론적이므로 동일하지만 저장값 우선)
        if (diMap.containsKey(agencyCode)) {
            log.debug("[DiGeneration] 기존 DI 반환: qimUserId={} agencyCode={}", qimUserId, agencyCode);
            return diMap.get(agencyCode);
        }

        // 신규 DI 생성
        String di = generateDi(qimUserId, agencyCode);
        log.info("[DiGeneration] 신규 DI 생성: qimUserId={} agencyCode={}", qimUserId, agencyCode);
        return di;
    }

    /**
     * 기관별 DI 생성 (순수 생성, 저장 없음)
     * HMAC-SHA256(agencyCode:qimUserId, secret) → Base64URL
     */
    public String generateDi(String qimUserId, String agencyCode) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(diSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String input = agencyCode + ":" + qimUserId;
            byte[] hash = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            log.error("[DiGeneration] DI 생성 실패: qimUserId={} agencyCode={}", qimUserId, agencyCode, e);
            throw new RuntimeException("DI 생성 실패", e);
        }
    }

    /**
     * di_map에 기관 DI 추가 후 JSON 직렬화 반환
     *
     * @param currentDiMapJson 현재 di_map JSON
     * @param agencyCode       기관 코드
     * @param di               생성된 DI 값
     * @return 갱신된 di_map JSON 문자열
     */
    public String addDiToMap(String currentDiMapJson, String agencyCode, String di) {
        Map<String, String> diMap = new HashMap<>(parseDiMap(currentDiMapJson));
        diMap.put(agencyCode, di);
        try {
            return objectMapper.writeValueAsString(diMap);
        } catch (Exception e) {
            log.error("[DiGeneration] di_map 직렬화 실패", e);
            throw new RuntimeException("di_map 직렬화 실패", e);
        }
    }

    /**
     * di_map JSON 파싱
     */
    public Map<String, String> parseDiMap(String diMapJson) {
        if (diMapJson == null || diMapJson.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(diMapJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("[DiGeneration] di_map JSON 파싱 실패 — 빈 맵 반환: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
